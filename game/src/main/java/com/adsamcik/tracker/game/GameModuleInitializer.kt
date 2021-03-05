package com.adsamcik.tracker.game

import android.content.Context
import android.content.IntentFilter
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.game.challenge.receiver.ChallengeSessionReceiver
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer

/**
 * Game module initializer
 */
@Suppress("unused")
class GameModuleInitializer : ModuleInitializer {
	@WorkerThread
	private fun initializeTrackerSessionReceivers(applicationContext: Context) {
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.ACTION_SESSION_FINAL)
		}

		applicationContext.registerReceiver(
				ChallengeSessionReceiver(),
				trackerSessionBroadcastFilter
		)
	}

	override fun initialize(context: Context) {
		initializeTrackerSessionReceivers(context)

		GoalTracker.initialize(context)
	}

}
