package com.adsamcik.tracker.tracker.source.coordinator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.recordFullDeletion
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.PendingSignalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsSessionFactWriterTransitionCoordinatorTest {
	private lateinit var database: AppDatabase
	private lateinit var catalog: ExecutableSourceLaneCatalog
	private lateinit var rolloutStore: RoomTrackingRolloutStateStore
	private lateinit var coordinator: StepsSessionFactWriterTransitionCoordinator

	@Before
	fun setUp() = runTest {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		catalog = ExecutableSourceLaneCatalog()
		rolloutStore = RoomTrackingRolloutStateStore(database, catalog)
		coordinator = StepsSessionFactWriterTransitionCoordinator(
			database,
			catalog,
			ReadyTrackingStartupGate,
		)
		database.sourceDestinationOwnerDao().insertIfAbsent(legacyOwner())
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `shadow promotion changes lane owner and rollout atomically`() = runTest {
		installShadow()

		val applied = activate().shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()

		applied.rolloutRevision shouldBe 3L
		applied.ownerGeneration shouldBe 2L
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe CANDIDATE_OWNER
			owner.ownerGeneration shouldBe 2L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
			lane.activatedRolloutRevision shouldBe 3L
			lane.captureAdmissionCutoffOrdinal shouldBe null
		}
		rolloutStore.load().let { rollout ->
			rollout.revision shouldBe 3L
			rollout.isCaptureReachable(
				SourceKind.STEPS,
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
			) shouldBe true
		}

		activate().shouldBeInstanceOf<StepsWriterTransitionResult.AlreadyApplied>()
		coordinator.activateCandidate(1L, 21L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()
			.blocker shouldBe StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED
	}

	@Test
	fun `cursor lag keeps legacy owner and inert shadow`() = runTest {
		installShadow()
		appendStepsWalEvent()

		val blocked = activate().shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()

		blocked.blocker shouldBe StepsWriterTransitionBlocker.SHADOW_CURSOR_BEHIND
		assertLegacyShadow()
	}

	@Test
	fun `in process legacy Steps producer blocks candidate activation`() = runTest {
		installShadow()
		val activeLegacyWriter = object : LegacyStepsWriterTransitionBoundary {
			override suspend fun <T : Any> runIfQuiescent(operation: suspend () -> T): T? = null
		}
		val guardedCoordinator = StepsSessionFactWriterTransitionCoordinator(
			database = database,
			executableLaneCatalog = catalog,
			startupGate = ReadyTrackingStartupGate,
			legacyStepsWriterBoundary = activeLegacyWriter,
		)

		val blocked = guardedCoordinator.activateCandidate(2L, 20L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()

		blocked.blocker shouldBe StepsWriterTransitionBlocker.LEGACY_STEPS_WRITER_NOT_QUIESCENT
		assertLegacyShadow()
		activate().shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
	}

	@Test
	fun `canonical boot identity cannot steal an unexpired prepared start lease`() = runTest {
		installShadow()
		database.sourceProjectionStateDao().insertLeaseIfAbsent(
			SourceCoordinatorLeaseEntity(
				leaseName = "tracking-session-coordinator",
				ownerToken = "prepared-start",
				acquiredAtMs = 500L,
				expiresAtMs = 2_000L,
				bootId = BOOT_ID,
				generation = 7L,
				acquiredElapsedRealtimeNanos = 500L,
				expiresElapsedRealtimeNanos = 2_000L,
			),
		)
		val canonicalClockCoordinator = StepsSessionFactWriterTransitionCoordinator(
			database = database,
			executableLaneCatalog = catalog,
			startupGate = ReadyTrackingStartupGate,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
			clock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000L),
		)

		canonicalClockCoordinator.activateCandidate(2L, 1_000L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Busy>()

		database.sourceProjectionStateDao().lease("tracking-session-coordinator")?.let { lease ->
			lease.ownerToken shouldBe "prepared-start"
			lease.bootId shouldBe BOOT_ID
			lease.generation shouldBe 7L
		}
	}

	@Test
	fun `lease time is sampled after startup reconciliation`() = runTest {
		installShadow()
		val clock = FixedClock(fixedTimeMillis = 1_000L, fixedRealtimeNanos = 1_000L)
		val slowStartupGate = object : TrackingStartupGate {
			override val isReady: Boolean = true
			override val currentGeneration: Long = 1L

			override suspend fun reconcile(retryFailedStorage: Boolean): TrackingStartupResult {
				clock.advance(31_000L)
				return TrackingStartupResult.Ready(false, 0L)
			}
		}
		val guardedCoordinator = StepsSessionFactWriterTransitionCoordinator(
			database = database,
			executableLaneCatalog = catalog,
			startupGate = slowStartupGate,
			bootClockDomainProvider = BootClockDomainProvider { BOOT_ID },
			clock = clock,
		)

		guardedCoordinator.activateCandidate(2L, 1_000L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		database.sourceProjectionStateDao().lease("tracking-session-coordinator")
			?.acquiredElapsedRealtimeNanos shouldBe clock.elapsedRealtimeNanos()
	}

	@Test
	fun `nonterminal logical session blocks a global rollout boundary`() = runTest {
		installShadow()
		database.sourceSessionDao().insertSession(activeSession())

		val blocked = activate().shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()

		blocked.blocker shouldBe StepsWriterTransitionBlocker.LIFECYCLE_NOT_IDLE
		assertLegacyShadow()
	}

	@Test
	fun `queued legacy steps command blocks owner promotion`() = runTest {
		installShadow()
		database.pendingSignalDao().insertAll(
			listOf(
				PendingSignalEntity(
					signalId = "queued-legacy-steps",
					sessionId = 1L,
					signalJson = "{}",
					createdAt = 1L,
					claimToken = "in-flight-writer",
					claimExpiresAt = 10_000L,
					stepsWriterOwner = LEGACY_OWNER,
					stepsWriterOwnerGeneration = 1L,
				),
			),
		)

		val blocked = activate().shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()

		blocked.blocker shouldBe StepsWriterTransitionBlocker.STEPS_COMMAND_PENDING
		assertLegacyShadow()
	}

	@Test
	fun `every activation checkpoint rolls back every writer authority row`() = runTest {
		installShadow()
		listOf(
			StepsWriterTransitionCheckpoint.AFTER_LANE_PROMOTION,
			StepsWriterTransitionCheckpoint.AFTER_OWNER_PROMOTION,
			StepsWriterTransitionCheckpoint.AFTER_CANONICAL_ROLLOUT_SAVE,
		).forEach { checkpoint ->
			shouldThrow<InjectedTransitionFailure> {
				failingAt(checkpoint).activateCandidate(2L, 20L)
			}

			assertLegacyShadow()
			database.trackingRolloutStateDao().get()?.revision shouldBe 2L
		}
	}

	@Test
	fun `incomplete service run and accepted lifecycle action independently block cutover`() = runTest {
		installShadow()
		database.sourceSessionDao().insertServiceRun(incompleteServiceRun())

		activate().shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()
			.blocker shouldBe StepsWriterTransitionBlocker.SERVICE_RUN_NOT_FINALIZED

		database.sourceSessionDao().deleteAllServiceRuns()
		database.sourceSessionDao().insertLifecycleActions(listOf(nonterminalLifecycleAction()))

		activate().shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()
			.blocker shouldBe StepsWriterTransitionBlocker.LIFECYCLE_ACTION_NOT_TERMINAL
	}

	@Test
	fun `rollback contains drains and restores legacy with a new generation`() = runTest {
		installShadow()
		activate()
		var drainRequests = 0
		coordinator = StepsSessionFactWriterTransitionCoordinator(
			database = database,
			executableLaneCatalog = catalog,
			startupGate = ReadyTrackingStartupGate,
			requestStepsDrain = { drainRequests += 1 },
		)

		val fenced = coordinator.beginCandidateRollback(
			expectedRolloutRevision = 3L,
			updatedAtMs = 30L,
		).shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		fenced.rolloutRevision shouldBe 4L
		fenced.cutoffOrdinal shouldBe 0L
		drainRequests shouldBe 1
		coordinator.beginCandidateRollback(2L, 31L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()
			.blocker shouldBe StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED
		// Generic rollout repair must not retire one half of the candidate rollback protocol.
		rolloutStore.load().stepsAreUnavailableForCapture() shouldBe true
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
			lane.captureAdmissionCutoffOrdinal shouldBe 0L
			lane.retentionRequired shouldBe true
		}
		shouldThrow<IllegalArgumentException> {
			rolloutStore.finishLaneRetirement(STEPS_BINDING, 0L, 35L)
		}

		val completed = coordinator.completeCandidateRollback(
			expectedContainedRolloutRevision = 4L,
			expectedCutoffOrdinal = 0L,
			updatedAtMs = 40L,
		).shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()

		completed.ownerGeneration shouldBe 3L
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 3L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
		database.sourceProjectionStateDao().productLane(
			STEPS_SOURCE,
			STEPS_BINDING.bindingGeneration,
			STEPS_BINDING.projectionId,
			STEPS_BINDING.projectionVersion,
		)?.let { retired ->
			retired.status shouldBe SourceProductProjectionLaneEntity.STATUS_RETIRED
			retired.terminalDisposition shouldBe
				SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN
		}
		coordinator.completeCandidateRollback(
			4L,
			0L,
			41L,
		).shouldBeInstanceOf<StepsWriterTransitionResult.AlreadyApplied>()
		coordinator.completeCandidateRollback(3L, 0L, 42L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()
			.blocker shouldBe StepsWriterTransitionBlocker.ROLLOUT_REVISION_CHANGED
	}

	@Test
	fun `rollback phase checkpoints leave their complete preceding authority state`() = runTest {
		installShadow()
		activate()
		listOf(
			StepsWriterTransitionCheckpoint.AFTER_CONTAINED_ROLLOUT_SAVE,
			StepsWriterTransitionCheckpoint.AFTER_ROLLBACK_CUTOFF,
		).forEach { checkpoint ->
			shouldThrow<InjectedTransitionFailure> {
				failingAt(checkpoint).beginCandidateRollback(3L, 30L)
			}
			rolloutStore.load().isCaptureReachable(
				SourceKind.STEPS,
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
			) shouldBe true
			database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)
				?.captureAdmissionCutoffOrdinal shouldBe null
		}

		coordinator.beginCandidateRollback(3L, 31L)
		listOf(
			StepsWriterTransitionCheckpoint.AFTER_CANDIDATE_LANE_RETIREMENT,
			StepsWriterTransitionCheckpoint.AFTER_LEGACY_OWNER_RESTORE,
		).forEach { checkpoint ->
			shouldThrow<InjectedTransitionFailure> {
				failingAt(checkpoint).completeCandidateRollback(4L, 0L, 40L)
			}
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.owner shouldBe
				CANDIDATE_OWNER
			database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.status shouldBe
				SourceProductProjectionLaneEntity.STATUS_ACTIVE
		}
	}

	@Test
	fun `rollback completion refuses a lane that has not drained to its cutoff`() = runTest {
		installShadow()
		activate()
		appendStepsWalEvent()
		val fence = coordinator.beginCandidateRollback(3L, 30L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		fence.cutoffOrdinal shouldBe 1L

		val blocked = coordinator.completeCandidateRollback(4L, 1L, 40L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Blocked>()

		blocked.blocker shouldBe StepsWriterTransitionBlocker.ROLLBACK_CURSOR_BEHIND
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.owner shouldBe
			CANDIDATE_OWNER
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.retentionRequired shouldBe true
	}

	@Test
	fun `full deletion rearms an empty candidate lane and advances the aba fence`() = runTest {
		installShadow()
		activate()
		appendStepsWalEvent()
		// Preserve a real AUTOINCREMENT gap: row count is not an admission high-water mark.
		database.sourceEventWalDao().deleteAll()
		appendStepsWalEvent()
		val admittedHighWater = requireNotNull(
			database.sourceEventWalDao().maximumAdmissionOrdinal(),
		)
		admittedHighWater shouldBe 2L
		database.sourceEventWalDao().countAll() shouldBe 1L
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = 1L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = admittedHighWater,
			updatedAtMs = 50L,
		)
		database.sourceEventWalDao().deleteAll()
		database.sourceProjectionStateDao().deleteAllProductLanes()

		coordinator.rearmAfterFullDeletion(60L)

		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe CANDIDATE_OWNER
			owner.ownerGeneration shouldBe 3L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL
			lane.activationOrdinal shouldBe 3L
			lane.contiguousAdmissionOrdinal shouldBe 2L
		}
		rolloutStore.load().let { rollout ->
			rollout.revision shouldBe 4L
			rollout.isCaptureReachable(
				SourceKind.STEPS,
				CaptureReachabilityMode.MANUAL_SESSION_CAPTURE,
			) shouldBe true
		}

		// A later deletion retry (for example, after diagnostics failed) must replace the empty
		// generation again rather than reusing the first re-arm's ABA identity.
		database.sourceEvidenceStateDao().recordFullDeletion(
			epoch = 2L,
			retainedFromMs = null,
			deletedSourceEventHighWaterOrdinal = admittedHighWater,
			updatedAtMs = 70L,
		)
		database.sourceProjectionStateDao().deleteAllProductLanes()
		coordinator.rearmAfterFullDeletion(80L)

		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe CANDIDATE_OWNER
			owner.ownerGeneration shouldBe 4L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.activationOrdinal shouldBe 3L
			lane.contiguousAdmissionOrdinal shouldBe 2L
		}
		rolloutStore.load().revision shouldBe 5L
	}

	@Test
	fun `full deletion under legacy ownership advances generation without inventing a lane`() = runTest {
		coordinator.rearmAfterFullDeletion(10L)

		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 2L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
		rolloutStore.load().stepsAreUnavailableForCapture() shouldBe true
	}

	@Test
	fun `full deletion during candidate retirement restores legacy containment`() = runTest {
		installShadow()
		activate()
		coordinator.beginCandidateRollback(3L, 30L)
			.shouldBeInstanceOf<StepsWriterTransitionResult.Applied>()
		database.sourceProjectionStateDao().deleteAllProductLanes()

		coordinator.rearmAfterFullDeletion(40L)

		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 3L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
		rolloutStore.load().let { rollout ->
			rollout.revision shouldBe 5L
			rollout.stepsAreUnavailableForCapture() shouldBe true
		}
	}

	@Test
	fun `every deletion rearm checkpoint rolls back lane owner and rollout together`() = runTest {
		installShadow()
		activate()
		database.sourceProjectionStateDao().deleteAllProductLanes()

		listOf(
			StepsWriterTransitionCheckpoint.AFTER_DELETION_LANE_REARM,
			StepsWriterTransitionCheckpoint.AFTER_DELETION_OWNER_REARM,
			StepsWriterTransitionCheckpoint.AFTER_DELETION_ROLLOUT_REARM,
		).forEach { checkpoint ->
			shouldThrow<InjectedTransitionFailure> {
				failingAt(checkpoint).rearmAfterFullDeletion(60L)
			}
			database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE) shouldBe null
			database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)
				?.ownerGeneration shouldBe 2L
			database.trackingRolloutStateDao().get()?.revision shouldBe 3L
		}
	}

	private suspend fun installShadow() {
		rolloutStore.load() shouldBe TrackingRolloutState.contained(revision = 1L)
		rolloutStore.installInertShadowLane(STEPS_BINDING, 2L, 10L)
	}

	private suspend fun activate(): StepsWriterTransitionResult = coordinator.activateCandidate(
		expectedRolloutRevision = 2L,
		updatedAtMs = 20L,
	)

	private suspend fun assertLegacyShadow() {
		database.sourceDestinationOwnerDao().get(STEPS_SOURCE, STEPS_DESTINATION)?.let { owner ->
			owner.owner shouldBe LEGACY_OWNER
			owner.ownerGeneration shouldBe 1L
		}
		database.sourceProjectionStateDao().activeProductLane(STEPS_SOURCE)?.let { lane ->
			lane.productStage shouldBe SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW
			lane.activatedRolloutRevision shouldBe 2L
		}
	}

	private suspend fun appendStepsWalEvent() {
		database.sourceEventWalDao().insertIgnoringDuplicate(
			SourceEventWalEntity(
				eventId = "steps-event-1",
				providerDedupKey = null,
				logicalTrackingId = null,
				serviceRunId = null,
				sourceKind = STEPS_SOURCE,
				sourceInstanceId = "steps-instance",
				registrationGeneration = 1L,
				sourceSequence = 1L,
				configRevision = 1L,
				planAttribution = 0,
				clockDomainId = BOOT_ID,
				observedElapsedNanos = 1L,
				receivedElapsedNanos = 1L,
				wallTimeMs = 1L,
				wallTimeUncertaintyMs = 0L,
				capturedCollectedDataEpoch = 0L,
				acquiredAtMs = 1L,
				qualityFlags = 0L,
				qualityConfidence = null,
				payloadVersion = 1,
				payload = byteArrayOf(1),
				payloadChecksum = "checksum",
				createdAtMs = 1L,
			),
		)
	}

	private fun failingAt(checkpoint: StepsWriterTransitionCheckpoint) =
		StepsSessionFactWriterTransitionCoordinator(
			database,
			catalog,
			ReadyTrackingStartupGate,
			StepsWriterTransitionFaultInjector { observed ->
				if (observed == checkpoint) throw InjectedTransitionFailure
			},
		)

	private fun incompleteServiceRun() = SourceServiceRunEntity(
		serviceRunId = "incomplete-run",
		logicalTrackingId = "orphan-session",
		state = SessionLifecycleState.ACTIVE.name,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		foregroundCapabilityFlags = 0L,
		startedAtMs = 1L,
		startedElapsedNanos = 1L,
		completedAtMs = null,
		completionReason = null,
	)

	private fun nonterminalLifecycleAction() = LifecycleDesiredActionEntity(
		actionId = "accepted-action",
		logicalTrackingId = "orphan-session",
		serviceRunId = "orphan-run",
		manifestRevision = 1L,
		actionRevision = 1L,
		actionFamily = LifecycleActionFamily.SOURCE_RUNTIME.name,
		sourceKind = STEPS_SOURCE,
		desiredState = LifecycleDesiredState.ACTIVE.name,
		desiredPlanRevision = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
		bootId = BOOT_ID,
		leaseGeneration = 1L,
		requestedAtMs = 1L,
		requestedElapsedRealtimeNanos = 1L,
		status = LifecycleActionStatus.START_ACCEPTED.name,
		attemptCount = 1,
		acknowledgedAtMs = 2L,
		acknowledgedElapsedRealtimeNanos = 2L,
		failureCode = null,
		retryTrigger = null,
		sourceInstanceId = "steps-instance",
		registrationGeneration = 1L,
	)

	private fun activeSession() = LogicalTrackingSessionEntity(
		logicalTrackingId = "active-session",
		state = SessionLifecycleState.STARTING.name,
		lifecycleRevision = 1L,
		desiredPlanRevision = 1L,
		rolloutRevision = 2L,
		startOrigin = SessionStartOrigin.MANUAL_FOREGROUND_START.name,
		clockDomainId = BOOT_ID,
		startedAtMs = 1L,
		startedElapsedNanos = 1L,
		cutoffAtMs = null,
		cutoffElapsedNanos = null,
		completedAtMs = null,
		finalAdmissionOrdinal = null,
		failureCode = null,
	)

	private fun legacyOwner() = SourceDestinationOwnerEntity(
		sourceKind = STEPS_SOURCE,
		destination = STEPS_DESTINATION,
		owner = LEGACY_OWNER,
		ownerGeneration = 1L,
		updatedAtMs = 0L,
	)

	private fun TrackingRolloutState.stepsAreUnavailableForCapture(): Boolean =
		!isCaptureReachable(SourceKind.STEPS, CaptureReachabilityMode.MANUAL_SESSION_CAPTURE)

	private companion object {
		const val BOOT_ID = "boot-1"
		const val STEPS_SOURCE = SourceDestinationOwnerEntity.SOURCE_STEPS
		const val STEPS_DESTINATION = SourceDestinationOwnerEntity.DESTINATION_SESSION_STEPS
		const val LEGACY_OWNER = SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL
		const val CANDIDATE_OWNER = SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		val STEPS_BINDING = ExecutableSourceLaneCatalog.STEPS_SESSION_FACTS
	}
}

private object InjectedTransitionFailure : RuntimeException()
