package com.adsamcik.signalcollector.game

import android.content.Context
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.module.ModuleInitializer
import com.adsamcik.signalcollector.game.challenge.receiver.ChallengeSessionReceiver

@Suppress("unused")
class GameModuleInitializer : ModuleInitializer {
	private fun initializeTrackerSessionReceivers(applicationContext: Context) {
		val localBroadcast = LocalBroadcastManager.getInstance(applicationContext)
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.RECEIVER_SESSION_STARTED)
			addAction(TrackerSession.RECEIVER_SESSION_ENDED)
		}

		localBroadcast.registerReceiver(ChallengeSessionReceiver(), trackerSessionBroadcastFilter)
	}

	override fun initialize(context: Context) {
		initializeTrackerSessionReceivers(context)
	}

}