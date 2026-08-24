package com.adsamcik.tracker.tracker.resilience

import android.app.ActivityManager
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTrackingStartupGuard @Inject constructor() : TrackingStartupGuard {
	@Volatile
	private var cachedResult: Boolean? = null
	@Volatile
	private var processSuppressed = false

	override fun wasForceStopped(context: android.content.Context): Boolean {
		cachedResult?.let { return it }
		return synchronized(this) {
			cachedResult ?: detectForceStop(context).also { cachedResult = it }
		}
	}

	override fun suppressAutoRecoveryForCurrentProcess() {
		processSuppressed = true
	}

	override fun isAutoRecoverySuppressed(context: android.content.Context): Boolean =
		processSuppressed || wasForceStopped(context)

	private fun detectForceStop(context: android.content.Context): Boolean {
		// There is no exact force-stop signal on API 26-34. Historical exit reasons on API
		// 30-34 remain ambiguous and are handled by ordinary previous-exit reconciliation.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
		return try {
			val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
			activityManager.getHistoricalProcessStartReasons(1)
				.firstOrNull()
				?.wasForceStopped() == true
		} catch (exception: RuntimeException) {
			false
		}
	}
}
