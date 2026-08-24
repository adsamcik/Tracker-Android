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

	fun isAutoRecoverySuppressed(context: Context): Boolean
}
