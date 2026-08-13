package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import com.adsamcik.tracker.shared.base.database.dao.PendingSignalDao
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class PreviousExitRecoveryAction {
	ENQUEUE_WAL_DRAIN,
	NONE,
}

fun recoveryActionForExitReason(reason: Int): PreviousExitRecoveryAction = when (reason) {
	ApplicationExitInfo.REASON_LOW_MEMORY,
	ApplicationExitInfo.REASON_SIGNALED,
	ApplicationExitInfo.REASON_CRASH,
	ApplicationExitInfo.REASON_CRASH_NATIVE,
	ApplicationExitInfo.REASON_ANR -> PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN
	else -> PreviousExitRecoveryAction.NONE
}

@Singleton
class PreviousExitRecoveryCoordinator @Inject constructor(
	private val activeSessionStore: ActiveTrackingSessionStore,
	private val pendingSignalDrainScheduler: PendingSignalDrainScheduler,
	private val forceStopSourceSessionFinalizer: ForceStopSourceSessionFinalizer,
	private val pendingSignalDaoProvider: Provider<PendingSignalDao>,
) {
	suspend fun handle(reason: Int): PreviousExitRecoveryAction {
		val action = recoveryActionForExitReason(reason)
		when (action) {
			PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN ->
				pendingSignalDrainScheduler.enqueueExpedited()
			PreviousExitRecoveryAction.NONE -> Unit
		}
		return action
	}

	/**
	 * Reconciles durable persistence independently of the prior process-exit classification.
	 *
	 * Android reports a user force-stop and an in-app/adb process stop through paths where automatic
	 * tracking recovery must remain suppressed. Already-admitted WAL rows are different: replaying
	 * them only completes committed storage work and never restarts collection.
	 */
	suspend fun enqueueDrainIfPending(): Boolean {
		if (!pendingSignalDaoProvider.get().hasAny()) return false
		pendingSignalDrainScheduler.enqueueExpedited()
		return true
	}

	suspend fun suppressAfterForceStop(
		completedAtMs: Long = System.currentTimeMillis(),
	): ForceStopSourceSessionFinalization {
		val finalization = forceStopSourceSessionFinalizer.finalize(completedAtMs)
		when (val result = activeSessionStore.clear()) {
			is ActiveTrackingSessionStoreResult.Success -> Unit
			is ActiveTrackingSessionStoreResult.Failure -> throw result.cause
		}
		return finalization
	}
}
