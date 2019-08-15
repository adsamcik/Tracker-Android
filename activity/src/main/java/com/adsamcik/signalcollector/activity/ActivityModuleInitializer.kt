package com.adsamcik.signalcollector.activity

import android.content.Context
import android.content.IntentFilter
import com.adsamcik.signalcollector.activity.receiver.ActivitySessionReceiver
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.module.ModuleInitializer

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
