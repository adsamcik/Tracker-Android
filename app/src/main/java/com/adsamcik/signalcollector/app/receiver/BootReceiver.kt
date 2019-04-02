package com.adsamcik.signalcollector.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.activity.LaunchActivity
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker

class BootReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
			TrackerLocker.initializeFromPersistence(context)
			ActivityWatcherService.poke(context)
			ActivityService.requestAutoTracking(context, LaunchActivity::class)
		}
	}
}
