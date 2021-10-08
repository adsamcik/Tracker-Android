package com.adsamcik.tracker.tracker.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.stopService
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.service.TrackerService

/**
 * Receives notification actions
 */
internal class TrackerNotificationReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		when (val value = intent.getIntExtra(ACTION_STRING, -1)) {
			STOP_TRACKING_ACTION -> {
				context.stopService<TrackerService>()
			}
			LOCK_RECHARGE_ACTION -> {
				TrackerLocker.lockUntilRecharge(context)
			}
			LOCK_TIME_ACTION -> {
				val minutes = intent.getIntExtra(STOP_MINUTES_EXTRA, -1)
				if (minutes > 0) {
					TrackerLocker.lockTimeLock(context, Time.MINUTE_IN_MILLISECONDS * minutes)
				}
			}
			else -> Reporter.report("Unknown value $value")
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

