package com.adsamcik.tracker.tracker.resilience

import android.app.ApplicationExitInfo
import com.adsamcik.tracker.tracker.source.runtime.SourceRegistrationRepository
import java.io.IOException
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
			is ActiveTrackingSessionStoreResult.Failure -> {
				if (stored.kind == ActiveTrackingSessionStoreFailureKind.UNAVAILABLE) {
					throw stored.cause
				}
				val finalization = previousExitSourceSessionFinalizer.finalizeStaleSessions(
					factualCompletedAtMs = completedAtMs,
					recoveryDescriptor = null,
				)
				confirmCorruptDescriptorReset()
				sourceRegistrationRepository.reconcilePriorProcessRegistrations()
				return finalization
			}
			is ActiveTrackingSessionStoreResult.Success -> stored.descriptor
		}
		val finalization = previousExitSourceSessionFinalizer.finalizeStaleSessions(
			factualCompletedAtMs = completedAtMs,
			recoveryDescriptor = storedDescriptor,
		)
		when (val disposition = finalization.descriptorDisposition) {
			PreviousExitRecoveryDescriptorDisposition.NoDescriptor,
			is PreviousExitRecoveryDescriptorDisposition.Preserved,
			-> Unit
			is PreviousExitRecoveryDescriptorDisposition.Clear ->
				when (val cleared = activeSessionStore.clearExact(disposition.descriptor)) {
				is ActiveTrackingSessionStoreResult.Success -> {
					check(cleared.descriptor != disposition.descriptor) {
						"Exact stale active-session descriptor was not cleared"
					}
				}
				is ActiveTrackingSessionStoreResult.Failure ->
					if (cleared.kind == ActiveTrackingSessionStoreFailureKind.CORRUPT) {
						confirmCorruptDescriptorReset()
					} else {
						throw cleared.cause
					}
			}
		}
		sourceRegistrationRepository.reconcilePriorProcessRegistrations()
		return finalization
	}

	private suspend fun confirmCorruptDescriptorReset() {
		when (val reset = activeSessionStore.resetCorruptState()) {
			is ActiveTrackingSessionStoreResult.Success -> {
				if (reset.descriptor != null) {
					throw IOException("Active-session corruption reset observed a current descriptor")
				}
			}
			is ActiveTrackingSessionStoreResult.Failure -> throw reset.cause
		}
	}

	suspend fun suppressAfterForceStop(
		completedAtMs: Long = System.currentTimeMillis(),
	): ForceStopSourceSessionFinalization {
		val finalization = forceStopSourceSessionFinalizer.finalize(completedAtMs)
		when (val result = activeSessionStore.clear()) {
			is ActiveTrackingSessionStoreResult.Success -> Unit
			is ActiveTrackingSessionStoreResult.Failure ->
				if (result.kind == ActiveTrackingSessionStoreFailureKind.CORRUPT) {
					confirmCorruptDescriptorReset()
				} else {
					throw result.cause
				}
		}
		sourceRegistrationRepository.reconcilePriorProcessRegistrations()
		return finalization
	}
}
