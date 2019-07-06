package com.adsamcik.signalcollector.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker

class TrackerTimeUnlockReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		TrackerLocker.unlockTimeLock(context)
	}

}
