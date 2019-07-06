package com.adsamcik.signalcollector

import android.content.Context
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.adsamcik.signalcollector.activity.receiver.ActivitySessionReceiver
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.module.ModuleInitializer

class AppModuleInitializer : ModuleInitializer {
	private fun initializeTrackerSessionReceivers(context: Context) {
		val localBroadcast = LocalBroadcastManager.getInstance(context)
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.RECEIVER_SESSION_ENDED)
		}

		localBroadcast.registerReceiver(ActivitySessionReceiver(), trackerSessionBroadcastFilter)
	}

	override fun initialize(context: Context) {
		initializeTrackerSessionReceivers(context)
	}


}