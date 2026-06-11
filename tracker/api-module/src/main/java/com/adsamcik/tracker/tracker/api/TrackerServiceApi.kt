package com.adsamcik.tracker.tracker.api

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
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

object TrackerServiceContract {
	const val SERVICE_CLASS_NAME = "com.adsamcik.tracker.tracker.service.TrackerService"
	const val ARG_IS_USER_INITIATED = "userInitiated"
	const val ARG_IS_AMBIENT = "isAmbient"
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
		// Use the restriction-tolerant start: background activity-recognition updates
		// can trigger this from a background-restricted context on Android 12+, where a
		// raw startForegroundService would throw ForegroundServiceStartNotAllowedException
		// and crash the delivering receiver. When blocked, the start is skipped; tracking
		// is re-triggered by the next activity transition or when the app is foregrounded.
		val intent = Intent().setClassName(context, TrackerServiceContract.SERVICE_CLASS_NAME).apply {
			putExtra(TrackerServiceContract.ARG_IS_USER_INITIATED, isUserInitiated)
			if (isAmbient) {
				putExtra(TrackerServiceContract.ARG_IS_AMBIENT, true)
			}
		}
		startForegroundServiceSafely(context, intent)
	}

	private fun startForegroundServiceSafely(context: Context, intent: Intent): Boolean {
		return try {
			ContextCompat.startForegroundService(context, intent)
			true
		} catch (exception: SecurityException) {
			Log.w("ForegroundServiceStart", "Foreground service start blocked by security policy", exception)
			false
		} catch (exception: RuntimeException) {
			val isForegroundStartRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
				exception::class.java.name == "android.app.ForegroundServiceStartNotAllowedException"
			if (isForegroundStartRestricted) {
				Log.w(
					"ForegroundServiceStart",
					"Skipped foreground service start from background-restricted context",
					exception,
				)
				false
			} else {
				throw exception
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
			info.service.className == TrackerServiceContract.SERVICE_CLASS_NAME
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
		context.stopService(Intent().setClassName(context, TrackerServiceContract.SERVICE_CLASS_NAME))
	}
}
