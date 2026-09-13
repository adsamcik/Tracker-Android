package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsFactImporterTest {
	private lateinit var database: AppDatabase
	private lateinit var broker: SourceBroker
	private lateinit var lifecycleStore: MutableAmbientLifecycleStore
	private lateinit var clock: FixedClock
	private lateinit var registration: AmbientStepsProviderRegistration
	private var policyElapsedRealtimeNanos = 1L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceDestinationOwnerDao().insertIfAbsent(
			SourceDestinationOwnerEntity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				destination = SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				owner = SourceDestinationOwnerEntity.OWNER_AMBIENT_STEPS_FACTS,
				ownerGeneration = SourceDestinationOwnerEntity.INITIAL_AMBIENT_STEPS_GENERATION,
				updatedAtMs = 0L,
			),
		)
		val rollout = installCanonicalProductLanesForTest(
			database = database,
			bindings = listOf(
				ExecutableSourceLaneBinding(
					source = SourceKind.STEPS,
					bindingGeneration = 1L,
					projectionId = "ambient-steps-import-test",
					projectionVersion = 1,
					captureModes = setOf(CaptureReachabilityMode.AMBIENT),
				),
			),
			rolloutRevision = 1L,
		)
		broker = SourceBroker(database, rollout)
		RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(
				bootId = BOOT_ID,
				elapsedRealtimeNanos = policyElapsedRealtimeNanos++,
				wallTimeMs = policyElapsedRealtimeNanos,
			)
		}.bootstrapFromLegacy(
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = true,
				legacySettingsMigrationCompleted = true,
			),
		)
		lifecycleStore = MutableAmbientLifecycleStore(
			CollectedDataLifecycleSnapshot(epoch = COLLECTED_DATA_EPOCH, retainedFromMs = null),
		)
		clock = FixedClock(OBSERVED_AT_MS, OBSERVED_AT_MS)
		val demand = activateDemand(elapsed = 1_000L, wall = 1_000L)
		val repository = AmbientStepsProviderRegistrationRepository(
			database = database,
			lifecycleStore = lifecycleStore,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		)
		registration = repository.reserve(
			provider = PROVIDER,
			expectedDemandId = demand.demand.demandId,
			boundary = demandBoundary(elapsed = 1_501L, wall = 1_501L),
		)
		repository.accept(registration)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `applies one bounded structural window from rounded privacy floor atomically`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, stepCount = 42L, observedAtMs)
		}
		clock.setTime(90_000_000L)

		val result = subject(reader).importNext(
			importBoundary(through = 90_000_000L, observedAt = 90_000_000L),
		) as AmbientStepsImportResult.Applied

		reader.windows shouldBe listOf(AmbientStepsProviderReadWindow(2_000L, 86_400_000L))
		result.window shouldBe reader.windows.single()
		result.semanticRevision shouldBe 1L
		val fact = database.ambientStepsFactRevisionDao().latest(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			result.logicalFactId,
		)
		requireNotNull(fact)
		fact.windowStartTimeMs shouldBe 2_000L
		fact.windowEndTimeMs shouldBe 86_400_000L
		fact.stepCount shouldBe 42L
		fact.authorizationRevision shouldBe registration.authorization.authorizationRevision
		fact.authorizationFingerprint shouldBe registration.authorization.authorizationFingerprint
		fact.sourcePolicyRevision shouldBe registration.state.appliedRevision
		fact.ambientConsentEpoch shouldBe 1L
		fact.collectedDataEpoch shouldBe COLLECTED_DATA_EPOCH
		AmbientStepsFactIntegrity.hasValidEffectChecksum(fact) shouldBe true
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.eligibleFromTimeMs shouldBe 2_000L
		cursor.importedThroughTimeMs shouldBe 86_400_000L
		cursor.cursorRevision shouldBe result.cursorRevision
		database.sourceEvidenceStateDao().get()?.collectedDataEpoch shouldBe COLLECTED_DATA_EPOCH
	}

	@Test
	fun `provider absence creates neither cursor nor covered zero`() = runTest {
		val reader = RecordingAmbientReader { _, _ -> null }

		subject(reader).importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.NoEvidence(
				AmbientStepsImportNoEvidenceReason.PROVIDER_RETURNED_NO_EVIDENCE,
			)

		reader.windows shouldBe listOf(AmbientStepsProviderReadWindow(2_000L, 5_000L))
		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
	}

	@Test
	fun `provider failure is retryable and leaves durable import state untouched`() = runTest {
		val reader = RecordingAmbientReader { _, _ -> error("provider unavailable") }

		subject(reader).importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Retryable(
				AmbientStepsImportRetryableReason.PROVIDER_READ_FAILED,
			)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
	}

	@Test
	fun `lifecycle rotation during provider read rejects stale result without mutation`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			lifecycleStore.set(
				CollectedDataLifecycleSnapshot(epoch = COLLECTED_DATA_EPOCH + 1L, retainedFromMs = 4_000L),
			)
			AmbientStepsProviderAggregate(PROVIDER, window, 12L, observedAtMs)
		}

		subject(reader).importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Stale(AmbientStepsImportStaleReason.LIFECYCLE_CHANGED)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
	}

	@Test
	fun `authorization rotation during provider read rejects stale result without mutation`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			broker.replaceAmbientStepsDemand(
				consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
				mechanism = null,
				bootId = BOOT_ID,
				elapsedRealtimeNanos = 4_000L,
				wallTimeMs = 4_000L,
			)
			AmbientStepsProviderAggregate(PROVIDER, window, 12L, observedAtMs)
		}

		subject(reader).importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Stale(AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
	}

	@Test
	fun `destination owner rotation during provider read rejects stale result without mutation`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			val dao = database.sourceDestinationOwnerDao()
			val owner = requireNotNull(
				dao.get(
					SourceDestinationOwnerEntity.SOURCE_STEPS,
					SourceDestinationOwnerEntity.DESTINATION_AMBIENT_STEPS,
				),
			)
			dao.compareAndSetOwner(
				sourceKind = owner.sourceKind,
				destination = owner.destination,
				expectedOwner = owner.owner,
				expectedOwnerGeneration = owner.ownerGeneration,
				newOwner = "test-replacement",
				newOwnerGeneration = owner.ownerGeneration + 1L,
				updatedAtMs = observedAtMs,
			) shouldBe 1
			AmbientStepsProviderAggregate(PROVIDER, window, 12L, observedAtMs)
		}

		subject(reader).importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Stale(
				AmbientStepsImportStaleReason.DESTINATION_OWNER_CHANGED,
			)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 0L
	}

	@Test
	fun `exact replay advances reconstructed cursor without another product revision`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 42L, observedAtMs)
		}
		val importer = subject(reader)
		importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		database.ambientStepsImportStateDao().deleteAllCursors()
		clock.setTime(9_000L)

		val result = importer.importNext(
			importBoundary(through = 5_000L, observedAt = 9_000L, observedElapsed = 9_000L),
		) as AmbientStepsImportResult.Unchanged

		result.window shouldBe AmbientStepsProviderReadWindow(2_000L, 5_000L)
		database.ambientStepsFactRevisionDao().countAll() shouldBe 1L
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.importedThroughTimeMs shouldBe 5_000L
		cursor.lastObservedAtMs shouldBe 9_000L
	}

	@Test
	fun `exact same-registration authorization boundary rotates before applying next fact`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		clock.setTime(3_000L)
		importer.importNext(
			importBoundary(through = 3_000L, observedAt = 3_000L, observedElapsed = 3_000L),
		) as AmbientStepsImportResult.Applied
		broker.replaceAmbientStepsDemand(
			consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
			mechanism = null,
			bootId = BOOT_ID,
			elapsedRealtimeNanos = 3_500L,
			wallTimeMs = 3_000L,
		)
		activateDemand(elapsed = 4_000L, wall = 3_000L)
		clock.setTime(5_000L)

		val second = importer.importNext(
			importBoundary(through = 4_000L, observedAt = 5_000L, observedElapsed = 5_000L),
		) as AmbientStepsImportResult.Applied

		second.window shouldBe AmbientStepsProviderReadWindow(3_000L, 4_000L)
		database.ambientStepsImportStateDao().authorityTransitions(
			registration.state.registrationGeneration,
		).shouldHaveSize(1)
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.continuitySegmentGeneration shouldBe 2L
		cursor.authorityTransitionSequence shouldBe 1L
		cursor.importedThroughTimeMs shouldBe 4_000L
		val secondFact = requireNotNull(
			database.ambientStepsFactRevisionDao().latest(
				AmbientStepsFactRevisionEntity.WRITER_ID,
				AmbientStepsFactRevisionEntity.WRITER_VERSION,
				second.logicalFactId,
			),
		)
		secondFact.continuitySegmentGeneration shouldBe 2L
		secondFact.authorizationRevision shouldBe cursor.authorizationRevision
	}

	@Test
	fun `undrained authorization boundary is an explicit gap and is not read across`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		clock.setTime(3_000L)
		importer.importNext(
			importBoundary(through = 3_000L, observedAt = 3_000L, observedElapsed = 3_000L),
		)
		broker.replaceAmbientStepsDemand(
			consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
			mechanism = null,
			bootId = BOOT_ID,
			elapsedRealtimeNanos = 3_500L,
			wallTimeMs = 4_000L,
		)
		activateDemand(elapsed = 4_000L, wall = 4_000L)
		val readsBefore = reader.windows.size

		importer.importNext(
			importBoundary(through = 5_000L, observedAt = 6_000L, observedElapsed = 6_000L),
		) shouldBe AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.AUTHORITY_BOUNDARY_NOT_DRAINED,
			fromTimeMs = 3_000L,
			toTimeMs = 4_000L,
		)

		reader.windows.size shouldBe readsBefore
		database.ambientStepsFactRevisionDao().countAll() shouldBe 1L
		database.ambientStepsImportStateDao().countAuthorityTransitions() shouldBe 0L
	}

	@Test
	fun `zone and retention discontinuities are typed without fabricating coverage`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		val readsBefore = reader.windows.size

		importer.importNext(
			importBoundary(
				through = 7_000L,
				observedAt = 8_000L,
				zoneId = ZoneId.of("Europe/Prague"),
			),
		) shouldBe AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.ZONE_CHANGED,
			fromTimeMs = 5_000L,
			toTimeMs = 5_000L,
		)
		lifecycleStore.set(
			CollectedDataLifecycleSnapshot(
				epoch = COLLECTED_DATA_EPOCH,
				retainedFromMs = 6_001L,
			),
		)
		importer.importNext(
			importBoundary(through = 8_000L, observedAt = 9_000L, observedElapsed = 9_000L),
		) shouldBe AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.RETENTION_ADVANCED,
			fromTimeMs = 5_000L,
			toTimeMs = 7_000L,
		)

		reader.windows.size shouldBe readsBefore
		database.ambientStepsFactRevisionDao().countAll() shouldBe 1L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
	}

	private fun subject(reader: RecordingAmbientReader) = AmbientStepsFactImporter(
		database = database,
		lifecycleStore = lifecycleStore,
		clock = clock,
		readers = mapOf(PROVIDER to reader),
	)

	private suspend fun activateDemand(
		elapsed: Long,
		wall: Long,
	): AmbientStepsDemandResult.Active = broker.replaceAmbientStepsDemand(
		consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
		mechanism = AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
		bootId = BOOT_ID,
		elapsedRealtimeNanos = elapsed,
		wallTimeMs = wall,
	) as AmbientStepsDemandResult.Active

	private fun importBoundary(
		through: Long = 5_000L,
		observedAt: Long = OBSERVED_AT_MS,
		observedElapsed: Long = observedAt,
		zoneId: ZoneId = ZoneId.of("UTC"),
	) = AmbientStepsImportBoundary(
		observedBootId = BOOT_ID,
		observedElapsedRealtimeNanos = observedElapsed,
		observedAtMs = observedAt,
		throughTimeMs = through,
		zoneId = zoneId,
	)

	private fun demandBoundary(elapsed: Long, wall: Long) = AmbientStepsDemandBoundary(
		bootId = BOOT_ID,
		elapsedRealtimeNanos = elapsed,
		wallTimeMs = wall,
	)

	private companion object {
		val PROVIDER = AmbientStepsProvider.LOCAL_RECORDING_STEPS
		const val BOOT_ID = "boot-ambient-import"
		const val COLLECTED_DATA_EPOCH = 3L
		const val OBSERVED_AT_MS = 8_000L
	}
}

private class RecordingAmbientReader(
	private val response: suspend (
		AmbientStepsProviderReadWindow,
		Long,
	) -> AmbientStepsProviderAggregate?,
) : AmbientStepsProviderReader {
	override val provider: AmbientStepsProvider = AmbientStepsProvider.LOCAL_RECORDING_STEPS
	val windows = mutableListOf<AmbientStepsProviderReadWindow>()

	override suspend fun read(
		window: AmbientStepsProviderReadWindow,
		observedAtMs: Long,
	): AmbientStepsProviderAggregate? {
		windows += window
		return response(window, observedAtMs)
	}
}

private class MutableAmbientLifecycleStore(initial: CollectedDataLifecycleSnapshot) :
	CollectedDataLifecycleStore {
	private val state = MutableStateFlow(initial)
	override val snapshots: Flow<CollectedDataLifecycleSnapshot> = state
	override suspend fun snapshot(): CollectedDataLifecycleSnapshot = state.value
	override suspend fun beginFullDeletion(deletedAtMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(epoch = state.value.epoch + 1L, retainedFromMs = deletedAtMs)
			.also { state.emit(it) }

	override suspend fun advanceRetainedFrom(retainedFromMs: Long): CollectedDataLifecycleSnapshot =
		state.value.copy(retainedFromMs = retainedFromMs).also { state.emit(it) }

	suspend fun set(snapshot: CollectedDataLifecycleSnapshot) {
		state.emit(snapshot)
	}
}
