package com.adsamcik.tracker.activity

import android.content.Context
import android.content.IntentFilter
import com.adsamcik.tracker.activity.receiver.ActivitySessionReceiver
import com.adsamcik.tracker.shared.base.data.NativeSessionActivity
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer

/**
 * Activity module initializer
 */
@Suppress("unused")
class ActivityModuleInitializer : ModuleInitializer {
	private fun initializeDatabase(context: Context) {
		val activityDao = AppDatabase.database(context).activityDao()

		val sessionActivity = NativeSessionActivity.values().map {
			it.getSessionActivity(context)
		}

		activityDao.insert(sessionActivity)
	}

	private fun initializeTrackerSessionReceivers(context: Context) {
		val trackerSessionBroadcastFilter = IntentFilter().apply {
			addAction(TrackerSession.ACTION_SESSION_ENDED)
		}

		context.registerReceiver(ActivitySessionReceiver(), trackerSessionBroadcastFilter)
	}

	/**
	 * Initializes activity module.
	 */
	override fun initialize(context: Context) {
		initializeTrackerSessionReceivers(context)
		initializeDatabase(context)
	}
}
