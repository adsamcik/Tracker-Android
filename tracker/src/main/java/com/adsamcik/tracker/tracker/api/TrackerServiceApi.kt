package com.adsamcik.tracker.tracker.api

import android.content.Context
import com.adsamcik.tracker.shared.base.extension.startForegroundService
import com.adsamcik.tracker.shared.base.extension.stopService
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.service.TrackerService

/**
 * Public API for Tracker Service.
 */
object TrackerServiceApi {
	/**
	 * Information about current tracking session. Null if no session is currently active.
	 */
	val sessionInfo: TrackerSessionInfo? get() = TrackerService.sessionInfo.value

	/**
	 * Indicates whether tracker service is active.
	 */
	val isActive: Boolean get() = TrackerService.isServiceRunning.value

	/**
	 * Starts tracker service in foreground.
	 */
	fun startService(context: Context, isUserInitiated: Boolean) {
		context.startForegroundService<TrackerService> {
			putExtra(TrackerService.ARG_IS_USER_INITIATED, isUserInitiated)
		}
	}

	/**
	 * Stops tracker service.
	 */
	fun stopService(context: Context) {
		context.stopService<TrackerService>()
	}
}
