package com.adsamcik.tracker.tracker.resilience

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import io.kotest.matchers.shouldBe
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
		insertRunningSession()
		val store = RecordingStore(null)
		val coordinator = PreviousExitRecoveryCoordinator(
			store,
			NoOpDrainScheduler,
			finalizer(),
		)

		coordinator.suppressAfterForceStop(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.FINALIZED

		val session = database.sourceSessionDao().session(LOGICAL_ID)
		session?.state shouldBe SessionLifecycleState.FINALIZED.name
		session?.lifecycleRevision shouldBe 4L
		session?.completedAtMs shouldBe 5_000L
		session?.failureCode shouldBe
			ForceStopSourceSessionFinalizer.FORCE_STOP_COMPLETION_REASON
		val run = database.sourceSessionDao().serviceRun(RUN_ID)
		run?.state shouldBe SessionLifecycleState.FINALIZED.name
		run?.completedAtMs shouldBe 5_000L
		run?.completionReason shouldBe
			ForceStopSourceSessionFinalizer.FORCE_STOP_COMPLETION_REASON
		store.descriptor shouldBe null
		store.clearCount shouldBe 1
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
	fun `database remains lazy until force-stop finalization is requested`() = runTest {
		var providerCalls = 0
		val finalizer = ForceStopSourceSessionFinalizer(
			Provider {
				providerCalls++
				database
			},
		)

		providerCalls shouldBe 0
		finalizer.finalize(completedAtMs = 5_000L) shouldBe
			ForceStopSourceSessionFinalization.NO_ACTIVE_SESSION
		providerCalls shouldBe 1
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
			),
		)
		insertServiceRun(
			serviceRunId = RUN_ID,
			state = SessionLifecycleState.ACTIVE,
			startedAtMs = 1_000L,
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

	private fun finalizer() = ForceStopSourceSessionFinalizer(Provider { database })

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
		override fun enqueueExpedited() = Unit
	}

	private companion object {
		const val LOGICAL_ID = "force-stopped-logical-session"
		const val RUN_ID = "force-stopped-service-run"
		const val OLDER_RUN_ID = "older-force-stopped-service-run"
		const val CLOSED_RUN_ID = "already-closed-service-run"
	}
}
