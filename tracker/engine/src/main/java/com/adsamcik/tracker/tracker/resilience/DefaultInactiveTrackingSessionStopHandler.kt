package com.adsamcik.tracker.tracker.resilience

import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finalizes only the logical lifecycle named by the exact out-of-Room recovery descriptor.
 *
 * Finalization happens before the descriptor is cleared, and the STOP is marked handled only
 * after both steps finish while its command-ordering gate is still held. If Room work fails, the
 * descriptor and STOP remain retryable. [clearExact] also prevents delayed cleanup from erasing a
 * descriptor written by a newer service run.
 */
@Singleton
class DefaultInactiveTrackingSessionStopHandler @Inject constructor(
	private val activeSessionStore: ActiveTrackingSessionStore,
	private val commandAuthority: TrackingLifecycleCommandAuthority,
	private val sourceSessionFinalizer: ExplicitStopSourceSessionFinalizer,
	private val clock: Clock,
	private val bootClockDomainProvider: BootClockDomainProvider,
) : InactiveTrackingSessionStopHandler {
	override suspend fun finalizeStoredSession(
		command: TrackingStopCommand,
	): InactiveTrackingSessionStopOutcome = when (
		val result = commandAuthority.completeActionableStop(command) {
			finalizeActionableStop(command)
		}
	) {
		is LockedTrackingStopResult.Handled -> result.value
		is LockedTrackingStopResult.StillActionable -> result.value
		LockedTrackingStopResult.Superseded -> InactiveTrackingSessionStopOutcome.SUPERSEDED
	}

	private suspend fun finalizeActionableStop(
		command: TrackingStopCommand,
	): TrackingStopActionResult<InactiveTrackingSessionStopOutcome> {
		val descriptor = when (val stored = activeSessionStore.read()) {
			is ActiveTrackingSessionStoreResult.Failure -> throw stored.cause
			is ActiveTrackingSessionStoreResult.Success -> stored.descriptor
		} ?: return TrackingStopActionResult.Terminal(
			InactiveTrackingSessionStopOutcome.NO_STORED_DESCRIPTOR,
		)

		val finalization = sourceSessionFinalizer.finalize(
			logicalTrackingId = descriptor.logicalTrackingId,
			expectedServiceRunId = descriptor.serviceRunId,
			requestedAtMs = command.requestedAtEpochMs,
			requestedBootId = command.requestedBootId,
			requestedElapsedRealtimeNanos = command.requestedElapsedRealtimeNanos,
			reconciliationAtMs = clock.currentTimeMillis(),
			reconciliationElapsedRealtimeNanos = clock.elapsedRealtimeNanos(),
			reconciliationBootId = bootClockDomainProvider.current(),
			reason = "$ABSENT_RUNTIME_REASON_PREFIX${command.reason.name}",
		)
		val outcome = when (finalization) {
			ExplicitStopSourceSessionFinalization.FINALIZED ->
				InactiveTrackingSessionStopOutcome.FINALIZED
			ExplicitStopSourceSessionFinalization.ALREADY_FINALIZED ->
				InactiveTrackingSessionStopOutcome.ALREADY_FINALIZED
			ExplicitStopSourceSessionFinalization.NO_ROOM_SESSION ->
				InactiveTrackingSessionStopOutcome.NO_ROOM_SESSION
			ExplicitStopSourceSessionFinalization.SESSION_MISMATCH ->
				InactiveTrackingSessionStopOutcome.SESSION_MISMATCH
		}
		if (finalization == ExplicitStopSourceSessionFinalization.SESSION_MISMATCH) {
			// A new service may have written its provisional descriptor before committing its Room
			// run. Without a successful exact Room match, clearing would destroy that newer recovery
			// key. Startup reconciliation owns genuinely orphaned descriptors.
			return TrackingStopActionResult.StillActionable(outcome)
		}

		when (val cleared = activeSessionStore.clearExact(descriptor)) {
			is ActiveTrackingSessionStoreResult.Failure -> throw cleared.cause
			is ActiveTrackingSessionStoreResult.Success -> check(cleared.descriptor != descriptor) {
				"Exact inactive-service descriptor was not cleared"
			}
		}
		return TrackingStopActionResult.Terminal(outcome)
	}

	companion object {
		const val ABSENT_RUNTIME_REASON_PREFIX = "SERVICE_ABSENT_"
	}
}
