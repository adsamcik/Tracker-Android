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
	@Volatile
	private var cachedEntryPoint: TrackerServiceApiEntryPoint? = null

	private fun getEntryPoint(context: Context): TrackerServiceApiEntryPoint {
		cachedEntryPoint?.let { return it }
		return synchronized(this) {
			cachedEntryPoint ?: EntryPointAccessors.fromApplication(
				context.applicationContext,
				TrackerServiceApiEntryPoint::class.java
			).also { cachedEntryPoint = it }
		}
	}
	
	private fun getController(context: Context): TrackerServiceController {
		return getEntryPoint(context).trackerServiceController()
	}

	private fun startServiceInternal(context: Context, isUserInitiated: Boolean, isAmbient: Boolean) {
		context.startForegroundService<TrackerService> {
			putExtra(TrackerService.ARG_IS_USER_INITIATED, isUserInitiated)
			if (isAmbient) {
				putExtra(TrackerService.ARG_IS_AMBIENT, true)
			}
		}
	}
	
	/**
	 * Information about current tracking session as Flow. Null if no session is currently active.
	 */
	fun sessionInfoFlow(context: Context): StateFlow<TrackerSessionInfo?> = 
		getController(context).sessionInfoFlow

	/**
	 * Indicates whether tracker service is active.
	 */
	fun isActive(context: Context): Boolean = 
		getController(context).isServiceRunning

	/**
	 * Checks the Android service manager directly to detect stale UI state after
	 * unexpected foreground-service termination.
	 *
	 * Uses the deprecated [ActivityManager.getRunningServices] because no modern
	 * replacement exists. Android deprecated it to prevent apps from discovering
	 * *other* apps' services, but it still returns the caller's own services.
	 */
	fun isRunningInSystem(context: Context): Boolean {
		val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
			?: return false
		@Suppress("DEPRECATION")
		val runningServices = activityManager.getRunningServices(Int.MAX_VALUE)
		return runningServices.any { info ->
			info.service.className == TrackerService::class.java.name
		}
	}

	/**
	 * Starts tracker service in foreground.
	 */
	fun startService(context: Context, isUserInitiated: Boolean) {
		startServiceInternal(context, isUserInitiated, isAmbient = false)
	}

	/**
	 * Starts tracker service in AMBIENT mode (steps + activity only, no GPS).
	 * Does not require location permission.
	 */
	fun startAmbientService(context: Context) {
		startServiceInternal(context, isUserInitiated = false, isAmbient = true)
	}

	/**
	 * Stops tracker service.
	 */
	fun stopService(context: Context) {
		context.stopService<TrackerService>()
	}
}
