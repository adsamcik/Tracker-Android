package com.adsamcik.tracker.tracker.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.controller.LockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives notification actions
 */
@AndroidEntryPoint
internal class TrackerNotificationReceiver : BroadcastReceiver() {
	
	@Inject
	lateinit var lockManager: LockManager
	
	override fun onReceive(context: Context, intent: Intent) {
		when (val value = intent.getIntExtra(ACTION_STRING, -1)) {
			STOP_TRACKING_ACTION -> {
				TrackerServiceApi.stopService(context)
			}
			LOCK_RECHARGE_ACTION -> {
				lockManager.lockUntilRecharge(context)
			}
			LOCK_TIME_ACTION -> {
				val minutes = intent.getIntExtra(STOP_MINUTES_EXTRA, -1)
				if (minutes > 0) {
					lockManager.lockTimeLock(context, Time.MINUTE_IN_MILLISECONDS * minutes)
				}
			}
			else -> Unit
		}
	}

	companion object {
		internal const val ACTION_STRING: String = "action"
		internal const val STOP_MINUTES_EXTRA: String = "stopForMinutes"

		internal const val STOP_TRACKING_ACTION: Int = 1
		internal const val LOCK_RECHARGE_ACTION: Int = 2
		internal const val LOCK_TIME_ACTION: Int = 3
	}
}
