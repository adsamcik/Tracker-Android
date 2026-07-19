package com.adsamcik.tracker.tracker.resilience

import android.content.Context

/**
 * Detects the first process launch after Android force-stopped the package.
 */
interface TrackingStartupGuard {
	fun wasForceStopped(context: Context): Boolean

	fun suppressAutoRecoveryForCurrentProcess()

	fun isAutoRecoverySuppressed(context: Context): Boolean
}
