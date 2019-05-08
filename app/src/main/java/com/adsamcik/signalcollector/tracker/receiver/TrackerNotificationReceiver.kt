package com.adsamcik.signalcollector.tracker.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adsamcik.signalcollector.common.Constants
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.TrackerService

class TrackerNotificationReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		when (val value = intent.getIntExtra(ACTION_STRING, -1)) {
			STOP_TRACKING_ACTION -> {
				context.stopService(Intent(context, TrackerService::class.java))
			}
			LOCK_RECHARGE_ACTION -> {
				TrackerLocker.lockUntilRecharge(context)
			}
			LOCK_TIME_ACTION -> {
				val minutes = intent.getIntExtra(STOP_MINUTES_EXTRA, -1)
				if (minutes > 0)
					TrackerLocker.lockTimeLock(context, Constants.MINUTE_IN_MILLISECONDS * minutes)
			}
			else -> Log.w(TAG, "Unknown value $value")
		}
	}

	companion object {
		private const val TAG = "SignalsReceiver"
		const val ACTION_STRING: String = "action"
		const val STOP_MINUTES_EXTRA: String = "stopForMinutes"

		const val STOP_TRACKING_ACTION: Int = 0
		const val LOCK_RECHARGE_ACTION: Int = 1
		const val LOCK_TIME_ACTION: Int = 2
	}
}
