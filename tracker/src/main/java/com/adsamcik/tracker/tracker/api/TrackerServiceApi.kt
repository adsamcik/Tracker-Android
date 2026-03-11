package com.adsamcik.tracker.tracker.api

import android.app.ActivityManager
import android.content.Context
import com.adsamcik.tracker.shared.base.extension.startForegroundService
import com.adsamcik.tracker.shared.base.extension.stopService
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.service.TrackerService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * Hilt EntryPoint for accessing TrackerServiceController from static context
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackerServiceApiEntryPoint {
	fun trackerServiceController(): TrackerServiceController
}

/**
 * Public API for Tracker Service.
 */
object TrackerServiceApi {
	
	private fun getController(context: Context): TrackerServiceController {
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			TrackerServiceApiEntryPoint::class.java
		)
		return entryPoint.trackerServiceController()
	}
	
	/**
	 * Information about current tracking session as Flow. Null if no session is currently active.
	 */
	fun sessionInfoFlow(context: Context): StateFlow<TrackerSessionInfo?> = 
		getController(context).sessionInfoFlow

	/**
	 * Information about current tracking session. Null if no session is currently active.
	 * @deprecated Use sessionInfoFlow instead. Direct value access will be removed in a future release.
	 */
	@Deprecated(
		message = "Use sessionInfoFlow(context) instead for reactive updates",
		replaceWith = ReplaceWith("sessionInfoFlow(context).value"),
		level = DeprecationLevel.WARNING
	)
	fun sessionInfo(context: Context): TrackerSessionInfo? = 
		getController(context).sessionInfoFlow.value

	/**
	 * Indicates whether tracker service is active.
	 */
	fun isActive(context: Context): Boolean = 
		getController(context).isServiceRunning

	/**
	 * Checks the Android service manager directly to detect stale UI state after
	 * unexpected foreground-service termination.
	 */
	@Suppress("DEPRECATION")
	fun isRunningInSystem(context: Context): Boolean {
		val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
			?: return false
		return activityManager.getRunningServices(Int.MAX_VALUE).any { info ->
			info.service.className == TrackerService::class.java.name
		}
	}

	/**
	 * Starts tracker service in foreground.
	 */
	fun startService(context: Context, isUserInitiated: Boolean) {
		context.startForegroundService<TrackerService> {
			putExtra(TrackerService.ARG_IS_USER_INITIATED, isUserInitiated)
		}
	}

	/**
	 * Starts tracker service in AMBIENT mode (steps + activity only, no GPS).
	 * Does not require location permission.
	 */
	fun startAmbientService(context: Context) {
		context.startForegroundService<TrackerService> {
			putExtra(TrackerService.ARG_IS_USER_INITIATED, false)
			putExtra(TrackerService.ARG_IS_AMBIENT, true)
		}
	}

	/**
	 * Stops tracker service.
	 */
	fun stopService(context: Context) {
		context.stopService<TrackerService>()
	}
}
