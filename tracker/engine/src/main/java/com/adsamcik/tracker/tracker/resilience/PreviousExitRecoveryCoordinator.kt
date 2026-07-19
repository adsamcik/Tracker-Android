package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import javax.inject.Inject
import javax.inject.Singleton

enum class PreviousExitRecoveryAction {
	ENQUEUE_WAL_DRAIN,
	SUPPRESS_RESTART,
	NONE,
}

fun recoveryActionForExitReason(reason: Int): PreviousExitRecoveryAction = when (reason) {
	ApplicationExitInfo.REASON_LOW_MEMORY,
	ApplicationExitInfo.REASON_SIGNALED,
	ApplicationExitInfo.REASON_CRASH,
	ApplicationExitInfo.REASON_CRASH_NATIVE,
	ApplicationExitInfo.REASON_ANR -> PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN
	ApplicationExitInfo.REASON_USER_REQUESTED -> PreviousExitRecoveryAction.SUPPRESS_RESTART
	else -> PreviousExitRecoveryAction.NONE
}

@Singleton
class PreviousExitRecoveryCoordinator @Inject constructor(
	private val activeSessionStore: ActiveTrackingSessionStore,
	private val pendingSignalDrainScheduler: PendingSignalDrainScheduler,
) {
	suspend fun handle(reason: Int): PreviousExitRecoveryAction {
		val action = recoveryActionForExitReason(reason)
		when (action) {
			PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN ->
				pendingSignalDrainScheduler.enqueueExpedited()
			PreviousExitRecoveryAction.SUPPRESS_RESTART ->
				activeSessionStore.clear()
			PreviousExitRecoveryAction.NONE -> Unit
		}
		return action
	}

	suspend fun suppressAfterForceStop() {
		activeSessionStore.clear()
	}
}

