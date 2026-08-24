package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository
import javax.inject.Inject
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
	private val previousExitSourceSessionFinalizer: PreviousExitSourceSessionFinalizer,
	private val sourceRegistrationRepository: SourceRegistrationRepository,
) {
	suspend fun handle(
		reason: Int,
		completedAtMs: Long,
		startupGeneration: Long,
	): PreviousExitRecoveryAction {
		reconcileStaleSessions(completedAtMs)
		val action = recoveryActionForExitReason(reason)
		when (action) {
			PreviousExitRecoveryAction.ENQUEUE_WAL_DRAIN ->
				pendingSignalDrainScheduler.enqueueExpedited(startupGeneration)
			PreviousExitRecoveryAction.NONE -> Unit
		}
		return action
	}

	/**
	 * Retires stale Room authority before source/provider recovery is allowed to run.
	 *
	 * Room finalization is atomic. DataStore cannot participate in that transaction, so the
	 * descriptor is cleared only by exact value; a newer concurrent descriptor is preserved.
	 */
	suspend fun reconcileStaleSessions(
		completedAtMs: Long? = null,
	): PreviousExitSourceSessionFinalization {
		val storedDescriptor = when (val stored = activeSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure -> throw stored.cause
			is ActiveTrackingSessionStoreResult.Success -> stored.descriptor
		}
		val finalization = previousExitSourceSessionFinalizer.finalizeStaleSessions(
			factualCompletedAtMs = completedAtMs,
			recoveryDescriptor = storedDescriptor,
		)
		val descriptorWasFinalized = storedDescriptor != null &&
			storedDescriptor.logicalTrackingId in finalization.finalizedLogicalTrackingIds
		val descriptorHasNoRoomSession = storedDescriptor != null &&
			finalization.inspectedLogicalTrackingId == storedDescriptor.logicalTrackingId &&
			finalization.inspectedSessionExists == false
		if (storedDescriptor != null &&
			(descriptorWasFinalized || descriptorHasNoRoomSession)
		) {
			when (val cleared = activeSessionStore.clearExact(storedDescriptor)) {
				is ActiveTrackingSessionStoreResult.Success -> Unit
				is ActiveTrackingSessionStoreResult.Failure -> throw cleared.cause
			}
		}
		sourceRegistrationRepository.reconcilePriorProcessRegistrations()
		return finalization
	}

	suspend fun suppressAfterForceStop(
		completedAtMs: Long = System.currentTimeMillis(),
	): ForceStopSourceSessionFinalization {
		val finalization = forceStopSourceSessionFinalizer.finalize(completedAtMs)
		when (val result = activeSessionStore.clear()) {
			is ActiveTrackingSessionStoreResult.Success -> Unit
			is ActiveTrackingSessionStoreResult.Failure -> throw result.cause
		}
		sourceRegistrationRepository.reconcilePriorProcessRegistrations()
		return finalization
	}
}
