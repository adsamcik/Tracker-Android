package com.adsamcik.tracker.tracker.resilience

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityAutomationEpochEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.source.coordinator.LifecycleActionStatus
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForceStopSourceSessionFinalizerTest {
	private lateinit var database: AppDatabase

	@Before
	fun setUp() {
		val application = ApplicationProvider.getApplicationContext<Application>()
		database = AppDatabase.testDatabase(application)
	}

	@After
	fun tearDown() = database.close()

	@Test
	fun `force-stop closes orphan Room session even after descriptor was already cleared`() = runTest {
		seedAutomationEpoch()
		insertRunningSession()
		val store = RecordingStore(null)
		val registrationRepository = mockk<SourceRegistrationRepository>(
			relaxed = true,
		)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			NoOpDrainScheduler,
			finalizer(),
			noOpPreviousExitFinalizer(),
			registrationRepository,
		)

		coordinator.suppressAfterForceStop(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.FINALIZED

		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.state shouldBe SessionLifecycleState.FINALIZED.name
		session?.lifecycleRevision shouldBe 4L
		session?.completedAtMs shouldBe 5_000L
		session?.cutoffElapsedNanos shouldBe null
		session?.failureCode shouldBe
			ForceStopSourceSessionFinalizer.FORCE_STOP_COMPLETION_REASON
		val run = database.sourceSessionDao().serviceRun(RUN_ID)
		run?.state shouldBe SessionLifecycleState.FINALIZED.name
		run?.completedAtMs shouldBe 5_000L
		run?.completionReason shouldBe
			ForceStopSourceSessionFinalizer.FORCE_STOP_COMPLETION_REASON
		store.descriptor shouldBe null
		store.clearCount shouldBe 1
		database.activityAutomationEpochDao().current()?.epoch shouldBe 18L
		coVerify(exactly = 1) {
			registrationRepository.reconcilePriorProcessRegistrations(any(), any())
		}
	}

	@Test
	fun `canonical Room cleanup also replaces a stale mismatched descriptor mirror`() = runTest {
		insertRunningSession()
		val store = RecordingStore(
			activeDescriptor(serviceRunId = "newer-run-not-owned-by-cleanup"),
		)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			NoOpDrainScheduler,
			finalizer(),
			noOpPreviousExitFinalizer(),
			mockk(relaxed = true),
		)

		coordinator.suppressAfterForceStop(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.FINALIZED

		database.sourceSessionDao().session(LOGICAL_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		database.sourceSessionDao().serviceRun(RUN_ID)?.state shouldBe
			SessionLifecycleState.FINALIZED.name
		store.descriptor shouldBe null
		store.clearCount shouldBe 1
	}

	@Test
	fun `force-stop closes every incomplete service run for the active logical session`() = runTest {
		insertRunningSession()
		insertServiceRun(
			serviceRunId = OLDER_RUN_ID,
			state = SessionLifecycleState.ACTIVE,
			startedAtMs = 500L,
		)
		insertServiceRun(
			serviceRunId = CLOSED_RUN_ID,
			state = SessionLifecycleState.FINALIZED,
			startedAtMs = 250L,
			completedAtMs = 750L,
			completionReason = "NORMAL_STOP",
		)

		finalizer().finalize(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.FINALIZED

		database.sourceSessionDao().incompleteServiceRuns(LOGICAL_ID) shouldBe emptyList()
		listOf(RUN_ID, OLDER_RUN_ID).forEach { serviceRunId ->
			val run = database.sourceSessionDao().serviceRun(serviceRunId)
			run?.state shouldBe SessionLifecycleState.FINALIZED.name
			run?.completedAtMs shouldBe 5_000L
			run?.completionReason shouldBe
				ForceStopSourceSessionFinalizer.FORCE_STOP_COMPLETION_REASON
		}
		val alreadyClosed = database.sourceSessionDao().serviceRun(CLOSED_RUN_ID)
		alreadyClosed?.completedAtMs shouldBe 750L
		alreadyClosed?.completionReason shouldBe "NORMAL_STOP"
	}

	@Test
	fun `force-stop terminalizes a lifecycle action awaiting foreground`() = runTest {
		insertRunningSession()
		insertAwaitingForegroundAction()

		finalizer().finalize(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.FINALIZED

		val action = database.sourceSessionDao().lifecycleAction(ACTION_ID)
		action?.status shouldBe LifecycleActionStatus.TERMINAL_FAILURE.name
		action?.failureCode shouldBe ForceStopSourceSessionFinalizer.FORCE_STOP_COMPLETION_REASON
	}

	@Test
	fun `database remains lazy until force-stop finalization is requested`() = runTest {
		var providerCalls = 0
		val finalizer = ForceStopSourceSessionFinalizer(
			Provider {
				providerCalls++
				database
			},
			FixedClock(5_000L, 5_000_000_000L),
			TestBootClockDomainProvider,
		)

		providerCalls shouldBe 0
		finalizer.finalize(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
		providerCalls shouldBe 1
	}

	@Test
	fun `force-stop retires session authority without inventing elapsed cutoff after reboot`() = runTest {
		insertRunningSession()
		database.sourceBrokerDao().insertDemands(listOf(sessionDemand()))
		val newBootFinalizer = ForceStopSourceSessionFinalizer(
			Provider { database },
			FixedClock(6_000L, 6_000_000_000L),
			object : BootClockDomainProvider {
				override fun current(): String = "boot-2"
			},
		)

		newBootFinalizer.finalize(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.FINALIZED

		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.cutoffAtMs shouldBe 5_000L
		session?.cutoffElapsedNanos shouldBe null
		database.sourceBrokerDao().demandHistory("session:$LOGICAL_ID").single().status shouldBe
			SourceDemandEntity.STATUS_RETIRED
	}

	private suspend fun insertRunningSession() {
		database.sourceSessionDao().insertSession(
			LogicalTrackingSessionEntity(
				logicalTrackingId = LOGICAL_ID,
				state = SessionLifecycleState.ACTIVE.name,
				lifecycleRevision = 3L,
				desiredPlanRevision = 7L,
				rolloutRevision = 2L,
				startOrigin = "MANUAL_FOREGROUND",
				clockDomainId = "boot-1",
				startedAtMs = 1_000L,
				startedElapsedNanos = 1_000_000L,
				cutoffAtMs = null,
				cutoffElapsedNanos = null,
				completedAtMs = null,
				finalAdmissionOrdinal = null,
				failureCode = null,
				currentServiceRunId = RUN_ID,
			),
		)
		insertServiceRun(
			serviceRunId = RUN_ID,
			state = SessionLifecycleState.ACTIVE,
			startedAtMs = 1_000L,
		)
	}

	private suspend fun seedAutomationEpoch() {
		database.activityAutomationEpochDao().ensure(
			ActivityAutomationEpochEntity(
				epoch = 17,
				automaticControlEnabled = true,
				lastRotationReason = "TEST_SEED",
			),
		)
	}

	private suspend fun insertAwaitingForegroundAction() {
		database.sourceSessionDao().insertLifecycleActions(
			listOf(
				LifecycleDesiredActionEntity(
					actionId = ACTION_ID,
					logicalTrackingId = LOGICAL_ID,
					serviceRunId = RUN_ID,
					manifestRevision = 1L,
					actionRevision = 1L,
					actionFamily = "SERVICE",
					sourceKind = null,
					desiredState = SessionLifecycleState.ACTIVE.name,
					desiredPlanRevision = 7L,
					sourcePolicyRevision = 1L,
					consentEpoch = null,
					startOrigin = "MANUAL_FOREGROUND",
					bootId = "boot-1",
					leaseGeneration = 1L,
					requestedAtMs = 1_000L,
					requestedElapsedRealtimeNanos = 1_000_000L,
					status = LifecycleActionStatus.AWAITING_FOREGROUND.name,
					attemptCount = 0,
					acknowledgedAtMs = null,
					acknowledgedElapsedRealtimeNanos = null,
					failureCode = null,
					retryTrigger = null,
					sourceInstanceId = null,
					registrationGeneration = null,
				),
			),
		)
	}

	private suspend fun insertServiceRun(
		serviceRunId: String,
		state: SessionLifecycleState,
		startedAtMs: Long,
		completedAtMs: Long? = null,
		completionReason: String? = null,
	) {
		database.sourceSessionDao().insertServiceRun(
			SourceServiceRunEntity(
				serviceRunId = serviceRunId,
				logicalTrackingId = LOGICAL_ID,
				state = state.name,
				desiredPlanRevision = 7L,
				rolloutRevision = 2L,
				foregroundCapabilityFlags = 1L,
				startedAtMs = startedAtMs,
				startedElapsedNanos = 1_000_000L,
				completedAtMs = completedAtMs,
				completionReason = completionReason,
			),
		)
	}

	private fun finalizer() = ForceStopSourceSessionFinalizer(
		Provider { database },
		FixedClock(5_000L, 5_000_000_000L),
		TestBootClockDomainProvider,
	)

	private fun sessionDemand() = SourceDemandEntity(
		demandId = "force-stop-demand",
		consumerId = "session:$LOGICAL_ID",
		sourceKind = 3,
		purpose = SourceBrokerPurpose.SESSION_CAPTURE,
		logicalTrackingId = LOGICAL_ID,
		serviceRunId = RUN_ID,
		manifestRevision = 1L,
		lifecycleLeaseGeneration = 1L,
		sourcePolicyRevision = 1L,
		consentEpoch = 1L,
		persistenceEligible = true,
		qosCode = 1,
		maximumAgeMs = 60_000L,
		desiredLatencyMs = 15_000L,
		requestedBootId = "boot-1",
		requestedElapsedRealtimeNanos = 1_000_000L,
		requestedAtMs = 1_000L,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
	)

	private fun noOpPreviousExitFinalizer() = mockk<PreviousExitSourceSessionFinalizer> {
		coEvery { finalizeStaleSessions(any(), any()) } returns
			PreviousExitSourceSessionFinalization(emptySet())
	}

	private fun activeDescriptor(serviceRunId: String = RUN_ID) =
		ActiveTrackingSessionDescriptor(
			isUserInitiated = true,
			isAmbient = false,
			policyTier = PolicyTier.PRECISION,
			logicalTrackingId = LOGICAL_ID,
			serviceRunId = serviceRunId,
		)

	private class RecordingStore(
		var descriptor: ActiveTrackingSessionDescriptor?,
	) : ActiveTrackingSessionStore {
		var clearCount = 0

		override suspend fun read(): ActiveTrackingSessionStoreResult =
			ActiveTrackingSessionStoreResult.Success(descriptor)

		override suspend fun save(
			descriptor: ActiveTrackingSessionDescriptor,
		): ActiveTrackingSessionStoreResult {
			this.descriptor = descriptor
			return ActiveTrackingSessionStoreResult.Success(descriptor)
		}

		override suspend fun clear(): ActiveTrackingSessionStoreResult {
			clearCount++
			descriptor = null
			return ActiveTrackingSessionStoreResult.Success(null)
		}
	}

	private object NoOpDrainScheduler : PendingSignalDrainScheduler {
		override fun enqueueExpedited(startupGeneration: Long) = Unit
	}

	private object TestBootClockDomainProvider : BootClockDomainProvider {
		override fun current(): String = "boot-1"
	}

	private fun noPendingSignalProvider(): Provider<PendingSignalDao> =
		Provider { database.pendingSignalDao() }

	private companion object {
		const val LOGICAL_ID = "force-stopped-logical-session"
		const val RUN_ID = "force-stopped-service-run"
		const val OLDER_RUN_ID = "older-force-stopped-service-run"
		const val CLOSED_RUN_ID = "already-closed-service-run"
		const val ACTION_ID = "awaiting-foreground-action"
	}
}
