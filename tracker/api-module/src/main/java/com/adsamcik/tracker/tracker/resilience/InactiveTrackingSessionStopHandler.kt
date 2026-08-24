package com.adsamcik.tracker.tracker.resilience

/**
 * Completes an explicit stop when Android no longer has the tracker service instance.
 *
 * Implementations must use the complete descriptor currently held by
 * [ActiveTrackingSessionStore] as the only authority key. A delayed stop must never infer or
 * finalize a different active Room lifecycle.
 */
interface InactiveTrackingSessionStopHandler {
	suspend fun finalizeStoredSession(
		command: TrackingStopCommand,
	): InactiveTrackingSessionStopOutcome
}

/** Durable result of handling the exact descriptor observed for an inactive service. */
enum class InactiveTrackingSessionStopOutcome {
	NO_STORED_DESCRIPTOR,
	NO_ROOM_SESSION,
	SUPERSEDED,
	FINALIZED,
	ALREADY_FINALIZED,
	SESSION_MISMATCH,
}
