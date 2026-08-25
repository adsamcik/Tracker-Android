package com.adsamcik.tracker.tracker.resilience

import android.content.Context

/**
 * Reads positive platform evidence for the first launch after a package force-stop.
 *
 * Android exposes exact `wasForceStopped` evidence only on API 35+. Consequently `false` means
 * "not confirmed", not "confirmed not force-stopped", on API 26-34. Callers must route older
 * ambiguous exits through ordinary previous-exit cleanup rather than claiming force-stop proof.
 */
interface TrackingStartupGuard {
	fun wasForceStopped(context: Context): Boolean

	fun suppressAutoRecoveryForCurrentProcess()

	/**
	 * Records that this process reached the foreground through an app launch.
	 *
	 * This is deliberately only intent: force-stop suppression remains closed through
	 * [awaitAutoRecoveryAuthorizationAfterStartupReady] and opens only inside the exact Ready
	 * generation that accepts the automatic-control handoff. Background receivers and workers must
	 * never call this method.
	 */
	fun recordExplicitForegroundLaunch(context: Context): Boolean

	/**
	 * Returns ordinary startup immediately, or waits for and consumes the one foreground launch that
	 * may release a confirmed force-stop. The caller must first establish a durable `Ready` startup
	 * generation so stale session authority is terminal before any app-scoped control is restored.
	 */
	suspend fun awaitAutoRecoveryAuthorizationAfterStartupReady(
		context: Context,
	): TrackingAutoRecoveryAuthorization

	/**
	 * Idempotently releases the consumed foreground authorization inside the exact startup `Ready`
	 * generation being handed to automatic-control initialization. Until this succeeds,
	 * [isAutoRecoverySuppressed] remains true and foreground permission repair cannot touch provider
	 * state.
	 */
	fun releaseAutoRecoveryForReadyGeneration(context: Context): Boolean

	fun isAutoRecoverySuppressed(context: Context): Boolean
}

enum class TrackingAutoRecoveryAuthorization {
	ORDINARY_START,
	EXPLICIT_FOREGROUND_AFTER_FORCE_STOP,
}
