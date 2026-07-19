package com.adsamcik.tracker.testing.fake

import android.content.Context
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard

class FakeTrackingStartupGuard(
	var forceStopped: Boolean = false,
) : TrackingStartupGuard {
	var processSuppressed: Boolean = false
		private set

	override fun wasForceStopped(context: Context): Boolean = forceStopped

	override fun suppressAutoRecoveryForCurrentProcess() {
		processSuppressed = true
	}

	override fun isAutoRecoverySuppressed(context: Context): Boolean =
		forceStopped || processSuppressed
}
