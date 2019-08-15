package com.adsamcik.tracker.game

import android.content.Context
import android.content.IntentFilter
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.module.ModuleInitializer
import com.adsamcik.tracker.game.challenge.receiver.ChallengeSessionReceiver

@Suppress("unused")
class GameModuleInitializer : ModuleInitializer {
	@WorkerThread
	private fun initializeTrackerSessionReceivers(applicationContext: Context) {
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.RECEIVER_SESSION_STARTED)
			addAction(TrackerSession.RECEIVER_SESSION_ENDED)

		}

		applicationContext.registerReceiver(ChallengeSessionReceiver(), trackerSessionBroadcastFilter)
	}

	override fun initialize(context: Context) {
		initializeTrackerSessionReceivers(context)
	}

}
