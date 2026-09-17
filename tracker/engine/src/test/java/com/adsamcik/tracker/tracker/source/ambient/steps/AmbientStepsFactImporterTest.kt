package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerRead
import com.adsamcik.tracker.shared.base.database.StepsCountDomainSchema
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.model.steps.StepsCounterDomainToken
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneBinding
import com.adsamcik.tracker.tracker.source.coordinator.installCanonicalProductLanesForTest
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import com.adsamcik.tracker.tracker.source.runtime.TestLiveAmbientRetentionAuthorityReader
import com.adsamcik.tracker.tracker.source.runtime.LiveAmbientRetentionSnapshot
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
	private lateinit var retentionSnapshot: LiveAmbientRetentionSnapshot
	private var policyElapsedRealtimeNanos = 1L

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		database.sourceEvidenceStateDao().ensure(
			com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState(
				collectedDataEpoch = COLLECTED_DATA_EPOCH,
				updatedAtMs = 1L,
			),
		)
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
		val retentionReader = TestLiveAmbientRetentionAuthorityReader()
		broker = SourceBroker(database, rollout, retentionReader)
		val policy = RoomSourcePolicyRepository(database) {
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
		retentionSnapshot = retentionReader.installCurrent(
			database,
			TrackingSourceComponent.STEPS,
			policy.revision,
			requireNotNull(policy[TrackingSourceComponent.STEPS].ambientConsentEpoch),
			COLLECTED_DATA_EPOCH,
			BOOT_ID,
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
			sourceBroker = broker,
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
		StepsCountDomainSchema.createStatements.forEach {
			database.openHelper.writableDatabase.execSQL(it)
		}
		val counterDomainToken =
			StepsCounterDomainToken.opaque("sha256:${"a".repeat(64)}")
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(
				provider = PROVIDER,
				window = window,
				stepCount = 42L,
				observedAtMs = observedAtMs,
				counterDomainToken = counterDomainToken,
			)
		}
		val importer = subject(reader)
		primeZone(importer)
		clock.setTime(90_000_000L)

		val result = importer.importNext(
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
		val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
			fact.writerId,
			fact.writerVersion,
			fact.logicalFactId,
		)
		val receiptRead = StepsCountDomainStore(database).readOwners(
			listOf(
				StepsCountDomainOwnerLookupKey(
					StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
					ownerIdentity,
					fact.semanticRevision,
				),
			),
		) as StepsCountDomainOwnerRead.Ready
		receiptRead.owners.values.single().receipt?.domainIdentity shouldBe
			counterDomainToken.encoded
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
	fun `provider aggregate without authenticated counter epoch remains terminal unproven`() =
		runTest {
			StepsCountDomainSchema.createStatements.forEach {
				database.openHelper.writableDatabase.execSQL(it)
			}
			val reader = RecordingAmbientReader { window, observedAtMs ->
				AmbientStepsProviderAggregate(PROVIDER, window, 42L, observedAtMs)
			}
			val importer = subject(reader)
			primeZone(importer)
			clock.setTime(90_000_000L)
			val result = importer.importNext(
				importBoundary(through = 90_000_000L, observedAt = 90_000_000L),
			) as AmbientStepsImportResult.Applied
			val fact = requireNotNull(
				database.ambientStepsFactRevisionDao().latest(
					AmbientStepsFactRevisionEntity.WRITER_ID,
					AmbientStepsFactRevisionEntity.WRITER_VERSION,
					result.logicalFactId,
				),
			)
			val ownerIdentity = StepsCountDomainReceiptIntegrity.ambientFactOwnerIdentity(
				fact.writerId,
				fact.writerVersion,
				fact.logicalFactId,
			)
			val read = StepsCountDomainStore(database).readOwners(
				listOf(
					StepsCountDomainOwnerLookupKey(
						StepsCountDomainOwnerRevisionEntity.OWNER_AMBIENT_FACT,
						ownerIdentity,
						fact.semanticRevision,
					),
				),
			) as StepsCountDomainOwnerRead.Ready

			read.owners.values.single().owner.operation shouldBe
				StepsCountDomainOwnerRevisionEntity.OPERATION_UNPROVEN
			read.owners.values.single().receipt shouldBe null
		}

	@Test
	fun `provider absence creates no covered zero and later evidence remains importable`() = runTest {
		var stepCount: Long? = null
		val reader = RecordingAmbientReader { window, observedAtMs ->
			stepCount?.let { AmbientStepsProviderAggregate(PROVIDER, window, it, observedAtMs) }
		}
		val importer = subject(reader)
		primeZone(importer)

		importer.importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.NoEvidence(
				AmbientStepsImportNoEvidenceReason.PROVIDER_RETURNED_NO_EVIDENCE,
			)

		reader.windows shouldBe listOf(AmbientStepsProviderReadWindow(2_000L, 5_000L))
		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 1L
		database.ambientStepsImportStateDao().countGaps() shouldBe 0L
		requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		).importedThroughTimeMs shouldBe 2_000L

		stepCount = 12L
		clock.setTime(9_000L)
		val applied = importer.importNext(
			importBoundary(through = 5_000L, observedAt = 9_000L, observedElapsed = 9_000L),
		) as AmbientStepsImportResult.Applied
		applied.window shouldBe AmbientStepsProviderReadWindow(2_000L, 5_000L)
		applied.semanticRevision shouldBe 1L
		database.ambientStepsFactRevisionDao().countAll() shouldBe 1L
	}

	@Test
	fun `empty completed day becomes a gap and does not block later day evidence`() = runTest {
		var invocation = 0
		val reader = RecordingAmbientReader { window, observedAtMs ->
			if (invocation++ == 0) {
				null
			} else {
				AmbientStepsProviderAggregate(PROVIDER, window, 17L, observedAtMs)
			}
		}
		val importer = subject(reader)
		primeZone(importer)
		clock.setTime(86_405_000L)

		importer.importNext(
			importBoundary(
				through = 86_400_000L,
				observedAt = 86_401_000L,
				observedElapsed = 86_401_000L,
			),
		) shouldBe AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.PROVIDER_NO_EVIDENCE,
			fromTimeMs = 2_000L,
			toTimeMs = 86_400_000L,
		)

		val applied = importer.importNext(
			importBoundary(
				through = 86_404_000L,
				observedAt = 86_405_000L,
				observedElapsed = 86_405_000L,
			),
		) as AmbientStepsImportResult.Applied

		reader.windows shouldBe listOf(
			AmbientStepsProviderReadWindow(2_000L, 86_400_000L),
			AmbientStepsProviderReadWindow(86_400_000L, 86_404_000L),
		)
		applied.window shouldBe reader.windows.last()
		database.ambientStepsFactRevisionDao().countAll() shouldBe 1L
		val gap = database.ambientStepsImportStateDao().gaps(
			registration.state.registrationGeneration,
		).single()
		gap.reason shouldBe AmbientStepsImportGapEntity.REASON_PROVIDER_NO_EVIDENCE
		gap.gapStartTimeMs shouldBe 2_000L
		gap.gapEndTimeMs shouldBe 86_400_000L
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.importedThroughTimeMs shouldBe 86_404_000L
		cursor.continuitySegmentGeneration shouldBe 2L
	}

	@Test
	fun `provider failure is retryable and leaves durable import state untouched`() = runTest {
		val reader = RecordingAmbientReader { _, _ -> error("provider unavailable") }
		val importer = subject(reader)
		primeZone(importer)

		importer.importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Retryable(
				AmbientStepsImportRetryableReason.PROVIDER_READ_FAILED,
			)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 1L
		requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		).importedThroughTimeMs shouldBe 2_000L
	}

	@Test
	fun `lifecycle rotation during provider read rejects stale result without mutation`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			lifecycleStore.set(
				CollectedDataLifecycleSnapshot(epoch = COLLECTED_DATA_EPOCH + 1L, retainedFromMs = 4_000L),
			)
			AmbientStepsProviderAggregate(PROVIDER, window, 12L, observedAtMs)
		}

		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Stale(AmbientStepsImportStaleReason.LIFECYCLE_CHANGED)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 1L
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

		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Stale(AmbientStepsImportStaleReason.AUTHORIZATION_CHANGED)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 1L
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

		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Stale(
				AmbientStepsImportStaleReason.DESTINATION_OWNER_CHANGED,
			)

		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
		database.ambientStepsImportStateDao().countCursors() shouldBe 1L
	}

	@Test
	fun `same high-water replay advances observation without another product revision`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 42L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
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
	fun `identical high-water and observation replay is an idempotent no-op`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 42L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		val first = importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		val dao = database.ambientStepsImportStateDao()
		val before = requireNotNull(dao.cursor(registration.state.registrationGeneration))

		val replay = importer.importNext(importBoundary()) as AmbientStepsImportResult.Unchanged

		replay.window shouldBe AmbientStepsProviderReadWindow(2_000L, 5_000L)
		replay.cursorRevision shouldBe first.cursorRevision
		dao.cursor(registration.state.registrationGeneration) shouldBe before
		database.ambientStepsFactRevisionDao().countAll() shouldBe 1L
	}

	@Test
	fun `progressive read revises one stable structural fact instead of fragmenting windows`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			val count = if (window.endTimeMs == 5_000L) 10L else 15L
			AmbientStepsProviderAggregate(PROVIDER, window, count, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		val first = importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		clock.setTime(9_000L)

		val second = importer.importNext(
			importBoundary(through = 7_000L, observedAt = 9_000L, observedElapsed = 9_000L),
		) as AmbientStepsImportResult.Applied

		reader.windows shouldBe listOf(
			AmbientStepsProviderReadWindow(2_000L, 5_000L),
			AmbientStepsProviderReadWindow(2_000L, 7_000L),
		)
		second.logicalFactId shouldBe first.logicalFactId
		second.semanticRevision shouldBe 2L
		database.ambientStepsFactRevisionDao().revisions(
			AmbientStepsFactRevisionEntity.WRITER_ID,
			AmbientStepsFactRevisionEntity.WRITER_VERSION,
			first.logicalFactId,
		).map { it.windowEndTimeMs to it.stepCount } shouldBe listOf(
			5_000L to 10L,
			7_000L to 15L,
		)
	}

	@Test
	fun `exact same-registration authorization boundary rotates before applying next fact`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
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
	fun `provider no evidence does not lose an exact authorization transition`() = runTest {
		var returnEvidence = true
		val reader = RecordingAmbientReader { window, observedAtMs ->
			if (returnEvidence) AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs) else null
		}
		val importer = subject(reader)
		primeZone(importer)
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
		returnEvidence = false
		clock.setTime(5_000L)

		importer.importNext(
			importBoundary(through = 4_000L, observedAt = 5_000L, observedElapsed = 5_000L),
		) shouldBe AmbientStepsImportResult.NoEvidence(
			AmbientStepsImportNoEvidenceReason.PROVIDER_RETURNED_NO_EVIDENCE,
		)
		database.ambientStepsImportStateDao().countAuthorityTransitions() shouldBe 1L
		val transitioned = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		transitioned.importedThroughTimeMs shouldBe 3_000L
		transitioned.segmentStartTimeMs shouldBe 3_000L
		transitioned.authorityTransitionSequence shouldBe 1L

		returnEvidence = true
		clock.setTime(6_000L)
		val resumed = importer.importNext(
			importBoundary(through = 4_000L, observedAt = 6_000L, observedElapsed = 6_000L),
		) as AmbientStepsImportResult.Applied
		resumed.window shouldBe AmbientStepsProviderReadWindow(3_000L, 4_000L)
	}

	@Test
	fun `undrained authorization boundary is an explicit gap and is not read across`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
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
		database.ambientStepsImportStateDao().countGaps() shouldBe 1L
		database.ambientStepsImportStateDao().countAuthorityTransitions() shouldBe 1L
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.importedThroughTimeMs shouldBe 4_000L
		cursor.segmentStartTimeMs shouldBe 4_000L

		clock.setTime(7_000L)
		val resumed = importer.importNext(
			importBoundary(through = 5_000L, observedAt = 7_000L, observedElapsed = 7_000L),
		) as AmbientStepsImportResult.Applied
		resumed.window shouldBe AmbientStepsProviderReadWindow(4_000L, 5_000L)
	}

	@Test
	fun `first observed zone starts at its boundary and preserves the earlier interval as unknown`() =
		runTest {
			val reader = RecordingAmbientReader { window, observedAtMs ->
				AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
			}
			val importer = subject(reader)
			val prague = ZoneId.of("Europe/Prague")

			importer.importNext(
				importBoundary(through = 5_000L, observedAt = 8_000L, zoneId = prague),
			) shouldBe AmbientStepsImportResult.Gap(
				reason = AmbientStepsImportGapReason.INITIAL_ZONE_AUTHORITY_UNOBSERVED,
				fromTimeMs = 2_000L,
				toTimeMs = 5_000L,
			)

			reader.windows shouldBe emptyList()
			val gap = database.ambientStepsImportStateDao().gaps(
				registration.state.registrationGeneration,
			).single()
			gap.reason shouldBe AmbientStepsImportGapEntity.REASON_INITIAL_ZONE_AUTHORITY_UNOBSERVED
			gap.previousZoneId shouldBe AmbientStepsImportGapEntity.ZONE_AUTHORITY_UNOBSERVED
			gap.nextZoneId shouldBe prague.id
			val cursor = requireNotNull(
				database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
			)
			cursor.segmentStartTimeMs shouldBe 5_000L
			cursor.importedThroughTimeMs shouldBe 5_000L
			cursor.lastObservedZoneId shouldBe prague.id

			clock.setTime(9_000L)
			val applied = importer.importNext(
				importBoundary(
					through = 7_000L,
					observedAt = 9_000L,
					observedElapsed = 9_000L,
					zoneId = prague,
				),
			) as AmbientStepsImportResult.Applied
			applied.window shouldBe AmbientStepsProviderReadWindow(5_000L, 7_000L)
		}

	@Test
	fun `positive width zone change is durable and resumes only after its observed boundary`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		val readsBefore = reader.windows.size
		val prague = ZoneId.of("Europe/Prague")

		importer.importNext(
			importBoundary(
				through = 7_000L,
				observedAt = 8_000L,
				zoneId = prague,
			),
		) shouldBe AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.ZONE_CHANGED,
			fromTimeMs = 5_000L,
			toTimeMs = 7_000L,
		)
		reader.windows.size shouldBe readsBefore
		val gap = database.ambientStepsImportStateDao().gaps(
			registration.state.registrationGeneration,
		).single()
		gap.gapStartTimeMs shouldBe 5_000L
		gap.gapEndTimeMs shouldBe 7_000L
		gap.previousZoneId shouldBe "UTC"
		gap.nextZoneId shouldBe prague.id

		clock.setTime(9_000L)
		val resumed = importer.importNext(
			importBoundary(
				through = 8_000L,
				observedAt = 9_000L,
				observedElapsed = 9_000L,
				zoneId = prague,
			),
		) as AmbientStepsImportResult.Applied
		resumed.window shouldBe AmbientStepsProviderReadWindow(7_000L, 8_000L)
	}

	@Test
	fun `zero width zone transition cannot erase a later positive interval`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		val prague = ZoneId.of("Europe/Prague")

		importer.importNext(
			importBoundary(through = 5_000L, observedAt = 8_000L, zoneId = prague),
		) shouldBe AmbientStepsImportResult.Gap(
			reason = AmbientStepsImportGapReason.ZONE_CHANGED,
			fromTimeMs = 5_000L,
			toTimeMs = 5_000L,
		)
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.importedThroughTimeMs shouldBe 5_000L
		cursor.segmentStartTimeMs shouldBe 5_000L

		clock.setTime(9_000L)
		val resumed = importer.importNext(
			importBoundary(
				through = 7_000L,
				observedAt = 9_000L,
				observedElapsed = 9_000L,
				zoneId = prague,
			),
		) as AmbientStepsImportResult.Applied
		resumed.window shouldBe AmbientStepsProviderReadWindow(5_000L, 7_000L)
	}

	@Test
	fun `retention discontinuity is durable and resumes from rounded retained floor`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		importer.importNext(importBoundary()) as AmbientStepsImportResult.Applied
		val readsBefore = reader.windows.size
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
		val gap = database.ambientStepsImportStateDao().gaps(
			registration.state.registrationGeneration,
		).single()
		gap.reason shouldBe AmbientStepsImportGapEntity.REASON_PROVIDER_RETENTION_LOSS
		gap.gapStartTimeMs shouldBe 5_000L
		gap.gapEndTimeMs shouldBe 7_000L

		clock.setTime(10_000L)
		val resumed = importer.importNext(
			importBoundary(through = 8_000L, observedAt = 10_000L, observedElapsed = 10_000L),
		) as AmbientStepsImportResult.Applied
		resumed.window shouldBe AmbientStepsProviderReadWindow(7_000L, 8_000L)
	}

	private fun subject(reader: RecordingAmbientReader) = AmbientStepsFactImporter(
		database = database,
		lifecycleStore = lifecycleStore,
		clock = clock,
		readers = mapOf(PROVIDER to reader),
	)

	private suspend fun primeZone(importer: AmbientStepsFactImporter) {
		importer.importNext(
			importBoundary(through = 2_000L, observedAt = 2_000L, observedElapsed = 2_000L),
		) shouldBe AmbientStepsImportResult.NoEvidence(
			AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
		)
	}

	private suspend fun activateDemand(
		elapsed: Long,
		wall: Long,
	): AmbientStepsDemandResult.Active = broker.replaceAmbientStepsDemand(
		consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
		mechanism = AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
		bootId = BOOT_ID,
		elapsedRealtimeNanos = elapsed,
		wallTimeMs = wall,
		retentionSnapshot = retentionSnapshot,
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
