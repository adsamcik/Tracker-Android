package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.service.ActivityWatcherService

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
			TrackerLocker.initializeFromPersistence(context)
			ActivityWatcherService.poke(context)
		}
	}
}

