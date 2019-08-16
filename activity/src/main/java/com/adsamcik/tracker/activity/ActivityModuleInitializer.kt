package com.adsamcik.tracker.activity

import android.content.Context
import android.content.IntentFilter
import com.adsamcik.tracker.activity.receiver.ActivitySessionReceiver
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.module.ModuleInitializer

class ActivityModuleInitializer : ModuleInitializer {
	private fun initializeTrackerSessionReceivers(context: Context) {
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.RECEIVER_SESSION_ENDED)
		}

		context.registerReceiver(ActivitySessionReceiver(), trackerSessionBroadcastFilter)
	}

	override fun initialize(context: Context) {
		initializeTrackerSessionReceivers(context)
	}
}
