package com.adsamcik.tracker.points

import android.content.Context
import android.content.IntentFilter
import com.adsamcik.tracker.logger.LogData
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.module.ModuleInitializer

/**
 * Initializes broadcast receiver for points
 */
class PointsInitializer : ModuleInitializer {
	override fun initialize(context: Context) {
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.ACTION_SESSION_FINAL)
		}

		context.registerReceiver(PointsSessionReceiver(), trackerSessionBroadcastFilter)
	}
}
