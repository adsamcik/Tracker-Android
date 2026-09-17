package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
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
class AmbientStepsProviderHandoffCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var broker: SourceBroker
	private lateinit var lifecycleStore: HandoffLifecycleStore
	private lateinit var registrations: AmbientStepsProviderRegistrationRepository
	private lateinit var predecessor: AmbientStepsProviderRegistration
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
					projectionId = "ambient-steps-handoff-test",
					projectionVersion = 1,
					captureModes = setOf(CaptureReachabilityMode.AMBIENT),
				),
			),
			rolloutRevision = 1L,
		)
		broker = SourceBroker(database, rollout)
		val snapshot = RoomSourcePolicyRepository(database) {
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
		database.sourceEvidenceStateDao().ensure()
		database.sourceEvidenceStateDao().updateLifecycle(COLLECTED_DATA_EPOCH, null, 3L)
		database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.GrantLiveAmbient(
				opaquePolicyId = "test-retention",
				expectedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				expectedSourcePolicyRevision = snapshot.revision,
				expectedAmbientConsentEpoch = requireNotNull(
					snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
				),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 500L,
				effectiveWallTimeMs = 500L,
			),
		)
		lifecycleStore = HandoffLifecycleStore(
			CollectedDataLifecycleSnapshot(COLLECTED_DATA_EPOCH, retainedFromMs = null),
		)
		registrations = AmbientStepsProviderRegistrationRepository(
			database = database,
			lifecycleStore = lifecycleStore,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		)
		val demand = replaceDemand(
			AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
			1_000L,
		)
		predecessor = registrations.reserve(
			AmbientStepsProvider.LOCAL_RECORDING_STEPS,
			demand.demand.demandId,
			demandBoundary(1_501L),
		)
		registrations.accept(predecessor)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `final drain partitions predecessor fact and explicit remainder before successor`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				37L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		val successor = acceptReplacement(5_501L)

		val result = AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) as AmbientStepsProviderHandoffResult.Completed

		result.drainDisposition shouldBe AmbientStepsProviderDrainDisposition.PARTIAL
		result.successorStartTimeMs shouldBe 6_000L
		reader.windows shouldBe listOf(AmbientStepsProviderReadWindow(2_000L, 5_000L))
		val fact = requireNotNull(database.ambientStepsFactRevisionDao().latest(
			com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity.WRITER_ID,
			com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity.WRITER_VERSION,
			requireNotNull(result.logicalFactId),
		))
		fact.provider shouldBe AmbientStepsProvider.LOCAL_RECORDING_STEPS.name
		fact.windowStartTimeMs shouldBe 2_000L
		fact.windowEndTimeMs shouldBe 5_000L
		val predecessorGap = database.ambientStepsImportStateDao()
			.gaps(predecessor.state.registrationGeneration).single()
		predecessorGap.reason shouldBe
			AmbientStepsImportGapEntity.REASON_AUTHORITY_BOUNDARY_NOT_DRAINED
		predecessorGap.gapStartTimeMs shouldBe 5_000L
		predecessorGap.gapEndTimeMs shouldBe 6_000L
		val successorGap = database.ambientStepsImportStateDao()
			.gaps(successor.state.registrationGeneration).single()
		successorGap.reason shouldBe AmbientStepsImportGapEntity.REASON_PROVIDER_CHANGED
		successorGap.gapStartTimeMs shouldBe 6_000L
		successorGap.gapEndTimeMs shouldBe 6_000L
		successorGap.predecessorRegistrationGeneration shouldBe
			predecessor.state.registrationGeneration
		database.ambientStepsImportStateDao()
			.effectiveGapIntervals(successor.state.registrationGeneration) shouldBe emptyList()
		database.ambientStepsImportStateDao()
			.cursor(predecessor.state.registrationGeneration)?.status shouldBe
			AmbientStepsImportCursorEntity.STATUS_RETIRED
		database.ambientStepsImportStateDao()
			.cursor(successor.state.registrationGeneration)?.let { cursor ->
				cursor.status shouldBe AmbientStepsImportCursorEntity.STATUS_ACTIVE
				cursor.importedThroughTimeMs shouldBe 6_000L
				cursor.segmentStartTimeMs shouldBe 6_000L
			}
	}

	@Test
	fun `provider no evidence records the whole undrained predecessor interval`() = runTest {
		val reader = HandoffRecordingReader { _, _ -> null }
		val importer = subject(reader)
		primeZone(importer)
		val successor = acceptReplacement(5_501L)

		val result = AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) as AmbientStepsProviderHandoffResult.Completed

		result.drainDisposition shouldBe AmbientStepsProviderDrainDisposition.UNAVAILABLE
		result.drainedWindow shouldBe null
		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		val gap = database.ambientStepsImportStateDao()
			.gaps(predecessor.state.registrationGeneration).single()
		gap.gapStartTimeMs shouldBe 2_000L
		gap.gapEndTimeMs shouldBe 6_000L
	}

	@Test
	fun `one bounded old-provider read leaves a multi-day tail as a gap`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				100L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		val successor = acceptReplacement(90_000_001L)

		val result = AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 90_002_000L),
		) as AmbientStepsProviderHandoffResult.Completed

		result.drainDisposition shouldBe AmbientStepsProviderDrainDisposition.PARTIAL
		reader.windows shouldBe listOf(AmbientStepsProviderReadWindow(2_000L, 86_400_000L))
		val gap = database.ambientStepsImportStateDao()
			.gaps(predecessor.state.registrationGeneration).single()
		gap.gapStartTimeMs shouldBe 86_400_000L
		gap.gapEndTimeMs shouldBe 90_001_000L
	}

	@Test
	fun `final overlapping aggregate revises one stable predecessor fact exactly once`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				if (window.endTimeMs == 4_000L) 10L else 12L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		val initial = importer.importNext(
			importBoundary(throughTimeMs = 4_000L, observedAtMs = 4_000L),
		) as AmbientStepsImportResult.Applied
		val successor = acceptReplacement(5_000L)

		val result = AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 6_000L),
		) as AmbientStepsProviderHandoffResult.Completed

		result.drainDisposition shouldBe AmbientStepsProviderDrainDisposition.APPLIED
		result.logicalFactId shouldBe initial.logicalFactId
		val revisions = database.ambientStepsFactRevisionDao().revisions(
			com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity.WRITER_ID,
			com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity.WRITER_VERSION,
			initial.logicalFactId,
		)
		revisions.map { it.semanticRevision } shouldBe listOf(1L, 2L)
		revisions.last().windowStartTimeMs shouldBe 2_000L
		revisions.last().windowEndTimeMs shouldBe 5_000L
		revisions.last().stepCount shouldBe 12L
		database.ambientStepsImportStateDao()
			.gaps(predecessor.state.registrationGeneration) shouldBe emptyList()
	}

	@Test
	fun `exact covered cutover writes only zero-width provider metadata and never rereads`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				15L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary(5_000L, 5_000L)) as AmbientStepsImportResult.Applied
		reader.windows.clear()
		val successor = acceptReplacement(5_000L)

		val first = AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 6_000L),
		) as AmbientStepsProviderHandoffResult.Completed
		val replay = AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) as AmbientStepsProviderHandoffResult.Completed

		first.drainDisposition shouldBe AmbientStepsProviderDrainDisposition.ALREADY_COVERED
		replay.replayed shouldBe true
		reader.windows shouldBe emptyList()
		val providerGap = database.ambientStepsImportStateDao()
			.gaps(successor.state.registrationGeneration).single()
		providerGap.gapStartTimeMs shouldBe 5_000L
		providerGap.gapEndTimeMs shouldBe 5_000L
		database.ambientStepsImportStateDao()
			.effectiveGapIntervals(successor.state.registrationGeneration) shouldBe emptyList()
	}

	@Test
	fun `completed replay rejects successor authorization mismatch without rereading`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				15L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary(5_000L, 5_000L)) as AmbientStepsImportResult.Applied
		reader.windows.clear()
		val successor = acceptReplacement(5_000L)
		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 6_000L),
		) as AmbientStepsProviderHandoffResult.Completed
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_import_cursor SET authorization_fingerprint = ? " +
				"WHERE registration_generation = ?",
			arrayOf("f".repeat(64), successor.state.registrationGeneration),
		)

		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) shouldBe AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED,
		)
		reader.windows shouldBe emptyList()
	}

	@Test
	fun `completed replay rejects a missing successor provider marker without rereading`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				15L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary(5_000L, 5_000L)) as AmbientStepsImportResult.Applied
		reader.windows.clear()
		val successor = acceptReplacement(5_000L)
		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 6_000L),
		) as AmbientStepsProviderHandoffResult.Completed
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM ambient_steps_import_gap WHERE registration_generation = ?",
			arrayOf(successor.state.registrationGeneration),
		)

		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) shouldBe AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED,
		)
		reader.windows shouldBe emptyList()
	}

	@Test
	fun `completed replay rejects a missing predecessor gap sibling without rereading`() = runTest {
		val reader = HandoffRecordingReader { _, _ -> null }
		val importer = subject(reader)
		primeZone(importer)
		val successor = acceptReplacement(5_501L)
		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) as AmbientStepsProviderHandoffResult.Completed
		reader.windows.size shouldBe 1
		database.openHelper.writableDatabase.execSQL(
			"DELETE FROM ambient_steps_import_gap WHERE registration_generation = ?",
			arrayOf(predecessor.state.registrationGeneration),
		)

		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 8_000L),
		) shouldBe AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.CURSOR_CHANGED,
		)
		reader.windows.size shouldBe 1
	}

	@Test
	fun `completed replay rejects predecessor authority mismatch without rereading`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				15L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary(5_000L, 5_000L)) as AmbientStepsImportResult.Applied
		reader.windows.clear()
		val successor = acceptReplacement(5_000L)
		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 6_000L),
		) as AmbientStepsProviderHandoffResult.Completed
		database.openHelper.writableDatabase.execSQL(
			"UPDATE ambient_steps_import_cursor SET source_policy_revision = " +
				"source_policy_revision + 1 WHERE registration_generation = ?",
			arrayOf(predecessor.state.registrationGeneration),
		)

		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) shouldBe AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.AUTHORIZATION_CHANGED,
		)
		reader.windows shouldBe emptyList()
	}

	@Test
	fun `lifecycle change during provider read fences every handoff write`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			lifecycleStore.set(
				CollectedDataLifecycleSnapshot(COLLECTED_DATA_EPOCH + 1L, retainedFromMs = observedAtMs),
			)
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				4L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		val predecessorBefore = database.ambientStepsImportStateDao()
			.cursor(predecessor.state.registrationGeneration)
		val successor = acceptReplacement(5_501L)

		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) shouldBe AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.LIFECYCLE_CHANGED,
		)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
		database.ambientStepsImportStateDao()
			.cursor(predecessor.state.registrationGeneration) shouldBe predecessorBefore
		database.ambientStepsImportStateDao()
			.cursor(successor.state.registrationGeneration) shouldBe null
	}

	@Test
	fun `predecessor generation retirement during read fences the stale result`() = runTest {
		val reader = HandoffRecordingReader { window, observedAtMs ->
			val retiring = registrations.pendingRetirements().single()
			registrations.completeRetirement(retiring) shouldBe true
			AmbientStepsProviderAggregate(
				AmbientStepsProvider.LOCAL_RECORDING_STEPS,
				window,
				4L,
				observedAtMs,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		val predecessorBefore = database.ambientStepsImportStateDao()
			.cursor(predecessor.state.registrationGeneration)
		val successor = acceptReplacement(5_501L)

		AmbientStepsProviderHandoffCoordinator(importer).execute(
			handoffCommand(successor, observedAtMs = 7_000L),
		) shouldBe AmbientStepsProviderHandoffResult.Stale(
			AmbientStepsProviderHandoffStaleReason.CURRENT_STATE_CHANGED,
		)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
		database.ambientStepsImportStateDao()
			.cursor(predecessor.state.registrationGeneration) shouldBe predecessorBefore
		database.ambientStepsImportStateDao()
			.cursor(successor.state.registrationGeneration) shouldBe null
	}

	private fun subject(reader: HandoffRecordingReader) = AmbientStepsFactImporter(
		database = database,
		lifecycleStore = lifecycleStore,
		clock = FixedClock(100_000_000L, 100_000_000L),
		readers = mapOf(reader.provider to reader),
	)

	private suspend fun primeZone(importer: AmbientStepsFactImporter) {
		importer.importNext(importBoundary(2_000L, 2_000L)) shouldBe
			AmbientStepsImportResult.NoEvidence(AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE)
	}

	private suspend fun acceptReplacement(atMs: Long): AmbientStepsProviderRegistration {
		val demand = replaceDemand(AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS, atMs)
		val successor = registrations.reserve(
			AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS,
			demand.demand.demandId,
			demandBoundary(atMs),
		)
		val replaced = requireNotNull(registrations.accept(successor))
		replaced.registrationGeneration shouldBe predecessor.state.registrationGeneration
		replaced.status shouldBe ProviderRegistrationGenerationEntity.STATUS_ACTIVE
		return successor
	}

	private suspend fun replaceDemand(
		mechanism: AmbientStepsAcquisitionMechanism,
		atMs: Long,
	): AmbientStepsDemandResult.Active = broker.replaceAmbientStepsDemand(
		consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
		mechanism = mechanism,
		bootId = BOOT_ID,
		elapsedRealtimeNanos = atMs,
		wallTimeMs = atMs,
	) as AmbientStepsDemandResult.Active

	private fun handoffCommand(
		successor: AmbientStepsProviderRegistration,
		observedAtMs: Long,
	) = AmbientStepsProviderHandoffCommand(
		predecessorRegistrationGeneration = predecessor.state.registrationGeneration,
		successorRegistrationGeneration = successor.state.registrationGeneration,
		observedBootId = BOOT_ID,
		observedElapsedRealtimeNanos = observedAtMs,
		observedAtMs = observedAtMs,
		zoneId = ZoneId.of("UTC"),
	)

	private fun importBoundary(throughTimeMs: Long, observedAtMs: Long) = AmbientStepsImportBoundary(
		observedBootId = BOOT_ID,
		observedElapsedRealtimeNanos = observedAtMs,
		observedAtMs = observedAtMs,
		throughTimeMs = throughTimeMs,
		zoneId = ZoneId.of("UTC"),
	)

	private fun demandBoundary(atMs: Long) = AmbientStepsDemandBoundary(
		bootId = BOOT_ID,
		elapsedRealtimeNanos = atMs,
		wallTimeMs = atMs,
	)

	private companion object {
		const val BOOT_ID = "boot-ambient-handoff"
		const val COLLECTED_DATA_EPOCH = 9L
	}
}

private class HandoffRecordingReader(
	private val response: suspend (
		AmbientStepsProviderReadWindow,
		Long,
	) -> AmbientStepsProviderAggregate?,
) : AmbientStepsProviderReader {
	override val provider = AmbientStepsProvider.LOCAL_RECORDING_STEPS
	val windows = mutableListOf<AmbientStepsProviderReadWindow>()

	override suspend fun read(
		window: AmbientStepsProviderReadWindow,
		observedAtMs: Long,
	): AmbientStepsProviderAggregate? {
		windows += window
		return response(window, observedAtMs)
	}
}

private class HandoffLifecycleStore(initial: CollectedDataLifecycleSnapshot) :
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
