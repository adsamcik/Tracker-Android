package com.adsamcik.tracker.game.goals.receiver

import android.content.Context
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.module.TrackerUpdateReceiver

internal class GoalsSessionUpdateReceiver : TrackerUpdateReceiver {
	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData
	) {
		GoalTracker.update(session)
	}
}
