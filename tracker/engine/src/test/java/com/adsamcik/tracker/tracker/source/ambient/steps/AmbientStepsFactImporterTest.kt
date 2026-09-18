package com.adsamcik.tracker.tracker.source.ambient.steps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.AmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.applyAmbientStepsRetentionDecision
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsImportGapEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleSnapshot
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.tracking.RoomSourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyEffectiveTime
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
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
import io.kotest.matchers.types.shouldBeInstanceOf
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
	private lateinit var policyRepository: RoomSourcePolicyRepository
	private var policyBootId = BOOT_ID
	private var policyElapsedRealtimeNanos = 1L
	private var policyWallTimeMs = 1L

	@Before
	fun setUp() = runTest {
		policyBootId = BOOT_ID
		policyElapsedRealtimeNanos = 1L
		policyWallTimeMs = 1L
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
		policyRepository = RoomSourcePolicyRepository(database) {
			SourcePolicyEffectiveTime(
				bootId = policyBootId,
				elapsedRealtimeNanos = policyElapsedRealtimeNanos++,
				wallTimeMs = policyWallTimeMs++,
			)
		}
		val snapshot = policyRepository.bootstrapFromLegacy(
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

	private suspend fun SourceBroker.replaceAmbientStepsDemand(
		consumerId: String,
		mechanism: AmbientStepsAcquisitionMechanism?,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientStepsDemandResult {
		val retention = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		return replaceAmbientStepsDemand(
			consumerId = consumerId,
			mechanism = mechanism,
			leaseIdentity = AmbientReconciliationIdentity(
				source = AmbientTrackingSource.STEPS,
				policyRevision = requireNotNull(retention.sourcePolicyRevision),
				consentEpoch = requireNotNull(retention.ambientConsentEpoch),
				collectedDataEpoch = retention.collectedDataEpoch,
				rolloutRevision = 1L,
				ownerCasToken = "ambient-steps-importer-test",
				executionRevision = 1L,
				retainedFromMs = retention.retainedFromMs,
				retentionPolicyId = retention.opaquePolicyId,
				retentionApprovalRevision = retention.approvalRevision,
			),
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)
	}

	@Test
	fun `applies one bounded structural window from rounded privacy floor atomically`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, stepCount = 42L, observedAtMs)
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
	fun `retention revocation rejects the next fact before provider read`() = runTest {
		val current = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.Revoke(
				scope = AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				expectedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 2_000L,
				effectiveWallTimeMs = 2_000L,
				expectedPreviousApprovalRevision = current.approvalRevision,
			),
		)
		var reads = 0
		val importer = subject(
			RecordingAmbientReader { window, observedAtMs ->
				reads++
				AmbientStepsProviderAggregate(PROVIDER, window, 1L, observedAtMs)
			},
		)

		importer.importNext(importBoundary()) shouldBe
			AmbientStepsImportResult.Ineligible(
				AmbientStepsImportIneligibleReason.RETENTION_AUTHORITY_UNAVAILABLE,
			)
		reads shouldBe 0
		database.ambientStepsFactRevisionDao().countAll() shouldBe 0L
	}

	@Test
	fun `unrelated policy revision and reboot retain exact older consent authority`() = runTest {
		policyBootId = "boot-2"
		policyElapsedRealtimeNanos = 1L
		policyWallTimeMs = 1_000L
		val current = policyRepository.currentState() as SourcePolicyAuthorityState.Active
		val consentEpoch = requireNotNull(
			current.snapshot[TrackingSourceComponent.STEPS].ambientConsentEpoch,
		)
		val revised = policyRepository.replaceCaptureSettings(
			current.snapshot.revision,
			TrackingParamsState(
				stepsEnabled = false,
				ambientStepsEnabled = true,
				minTimeSeconds = TrackingParamsState.DEFAULT_MIN_TIME + 1,
				legacySettingsMigrationCompleted = true,
			),
			reason = "TEST_UNRELATED_POLICY_CHANGE",
		)
		val priorRetention = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.GrantLiveAmbient(
				opaquePolicyId = "test-retention",
				expectedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				expectedSourcePolicyRevision = revised.revision,
				expectedAmbientConsentEpoch = consentEpoch,
				effectiveBootId = "boot-2",
				effectiveElapsedRealtimeNanos = 200L,
				effectiveWallTimeMs = 1_100L,
				expectedPreviousApprovalRevision = priorRetention.approvalRevision,
			),
		)
		val demand = activateDemand(
			elapsed = 300L,
			wall = 1_200L,
			bootId = "boot-2",
		)
		val repository = AmbientStepsProviderRegistrationRepository(
			database = database,
			lifecycleStore = lifecycleStore,
			bootClockDomainProvider = BootClockDomainProvider { "boot-2" },
		)
		registration = repository.reserve(
			provider = PROVIDER,
			expectedDemandId = demand.demand.demandId,
			boundary = demandBoundary(400L, 1_300L, "boot-2"),
		)
		repository.accept(registration)
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 7L, observedAtMs)
		}
		val importer = subject(reader)
		importer.importNext(
			importBoundary(
				through = 2_000L,
				observedAt = 2_000L,
				observedElapsed = 1_000L,
				bootId = "boot-2",
			),
		) shouldBe AmbientStepsImportResult.NoEvidence(
			AmbientStepsImportNoEvidenceReason.NO_WINDOW_AVAILABLE,
		)
		clock.setTime(8_000L)

		importer.importNext(
			importBoundary(
				through = 5_000L,
				observedAt = 8_000L,
				observedElapsed = 8_000L,
				bootId = "boot-2",
			),
		).shouldBeInstanceOf<AmbientStepsImportResult.Applied>()
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
	fun `retention-only rotation rejects stale authority and resumes after restart`() = runTest {
		val reader = RecordingAmbientReader { window, observedAtMs ->
			AmbientStepsProviderAggregate(PROVIDER, window, 10L, observedAtMs)
		}
		val importer = subject(reader)
		primeZone(importer)
		clock.setTime(3_000L)
		importer.importNext(
			importBoundary(through = 3_000L, observedAt = 3_000L, observedElapsed = 3_000L),
		) as AmbientStepsImportResult.Applied
		val previousRetention = requireNotNull(
			database.ambientStepsFactRevisionDao().latestRetentionAuthority(
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			),
		)
		database.applyAmbientStepsRetentionDecision(
			AmbientStepsRetentionDecision.GrantLiveAmbient(
				opaquePolicyId = "rotated-retention",
				expectedCollectedDataEpoch = COLLECTED_DATA_EPOCH,
				expectedSourcePolicyRevision = registration.state.appliedRevision,
				expectedAmbientConsentEpoch = requireNotNull(
					registration.authorization.authorizedMembers.single().consentEpoch,
				),
				effectiveBootId = BOOT_ID,
				effectiveElapsedRealtimeNanos = 3_500L,
				effectiveWallTimeMs = 3_000L,
				expectedPreviousApprovalRevision = previousRetention.approvalRevision,
			),
		)
		val readsBeforeStaleAttempt = reader.windows.size

		importer.importNext(
			importBoundary(through = 3_000L, observedAt = 3_600L, observedElapsed = 3_600L),
		) shouldBe AmbientStepsImportResult.Ineligible(
			AmbientStepsImportIneligibleReason.AUTHORIZATION_INELIGIBLE,
		)
		reader.windows.size shouldBe readsBeforeStaleAttempt

		val demand = activateDemand(elapsed = 4_000L, wall = 3_000L)
		val refreshedRegistration = AmbientStepsProviderRegistrationRepository(
			database = database,
			lifecycleStore = lifecycleStore,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
		).reserve(
			provider = PROVIDER,
			expectedDemandId = demand.demand.demandId,
			boundary = demandBoundary(elapsed = 4_500L, wall = 3_000L),
		)
		refreshedRegistration.requiresProviderAcceptance shouldBe false
		clock.setTime(5_000L)
		val resumed = subject(reader).importNext(
			importBoundary(through = 4_000L, observedAt = 5_000L, observedElapsed = 5_000L),
		) as AmbientStepsImportResult.Applied

		resumed.window shouldBe AmbientStepsProviderReadWindow(3_000L, 4_000L)
		val cursor = requireNotNull(
			database.ambientStepsImportStateDao().cursor(registration.state.registrationGeneration),
		)
		cursor.authorizationRevision shouldBe
			refreshedRegistration.authorization.authorizationRevision
		cursor.retentionPolicyId shouldBe "rotated-retention"
		cursor.retentionApprovalRevision shouldBe previousRetention.approvalRevision + 1L
		cursor.authorityTransitionSequence shouldBe 1L
		database.ambientStepsImportStateDao().countAuthorityTransitions() shouldBe 1L
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
		bootId: String = BOOT_ID,
	): AmbientStepsDemandResult.Active = broker.replaceAmbientStepsDemand(
		consumerId = AmbientStepsDemandReconciler.CONSUMER_ID,
		mechanism = AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS,
		bootId = bootId,
		elapsedRealtimeNanos = elapsed,
		wallTimeMs = wall,
	) as AmbientStepsDemandResult.Active

	private fun importBoundary(
		through: Long = 5_000L,
		observedAt: Long = OBSERVED_AT_MS,
		observedElapsed: Long = observedAt,
		zoneId: ZoneId = ZoneId.of("UTC"),
		bootId: String = BOOT_ID,
	) = AmbientStepsImportBoundary(
		observedBootId = bootId,
		observedElapsedRealtimeNanos = observedElapsed,
		observedAtMs = observedAt,
		throughTimeMs = through,
		zoneId = zoneId,
	)

	private fun demandBoundary(
		elapsed: Long,
		wall: Long,
		bootId: String = BOOT_ID,
	) = AmbientStepsDemandBoundary(
		bootId = bootId,
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
