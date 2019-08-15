package com.adsamcik.tracker.tracker.api

import android.content.Context
import com.adsamcik.tracker.common.extension.startForegroundService
import com.adsamcik.tracker.common.extension.stopService
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.service.TrackerService

object TrackerServiceApi {
	val sessionInfo: TrackerSessionInfo? get() = TrackerService.sessionInfo.value

	val isActive: Boolean get() = TrackerService.isServiceRunning.value

	fun startService(context: Context, isUserInitiated: Boolean) {
		context.startForegroundService<TrackerService> {
			putExtra(TrackerService.ARG_IS_USER_INITIATED, isUserInitiated)
		}
	}

	fun stopService(context: Context) {
		context.stopService<TrackerService>()
	}
}
