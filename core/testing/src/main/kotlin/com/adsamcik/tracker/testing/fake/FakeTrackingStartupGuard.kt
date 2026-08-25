package com.adsamcik.tracker.testing.fake

import android.content.Context
import com.adsamcik.tracker.tracker.resilience.TrackingAutoRecoveryAuthorization
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class FakeTrackingStartupGuard(
	var forceStopped: Boolean = false,
) : TrackingStartupGuard {
	var processSuppressed: Boolean = false
		private set
	private val explicitForegroundRecorded = MutableStateFlow(false)
	private var explicitForegroundAuthorized = false
	private var explicitForegroundReleased = false

	override fun wasForceStopped(context: Context): Boolean = forceStopped

	override fun suppressAutoRecoveryForCurrentProcess() {
		processSuppressed = true
	}

	override fun recordExplicitForegroundLaunch(context: Context): Boolean {
		if (!forceStopped || explicitForegroundRecorded.value) return false
		explicitForegroundRecorded.value = true
		return true
	}

	override suspend fun awaitAutoRecoveryAuthorizationAfterStartupReady(
		context: Context,
	): TrackingAutoRecoveryAuthorization {
		if (!forceStopped) return TrackingAutoRecoveryAuthorization.ORDINARY_START
		explicitForegroundRecorded.first { it }
		explicitForegroundAuthorized = true
		return TrackingAutoRecoveryAuthorization.EXPLICIT_FOREGROUND_AFTER_FORCE_STOP
	}

	override fun releaseAutoRecoveryForReadyGeneration(context: Context): Boolean {
		if (explicitForegroundReleased) return true
		if (!forceStopped || !explicitForegroundAuthorized) return false
		explicitForegroundReleased = true
		processSuppressed = false
		return true
	}

	override fun isAutoRecoverySuppressed(context: Context): Boolean =
		!explicitForegroundReleased && (forceStopped || processSuppressed)
}
