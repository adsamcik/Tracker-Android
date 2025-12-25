package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.tracker.service.ActivityWatcherService

class BootReceiver : BroadcastReceiver() {
	@OptIn(ExperimentalStdlibApi::class)
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
			val lockManager = (context.applicationContext as Application).appGraph.lockManager
			lockManager.initializeFromPersistence(context)
			ActivityWatcherService.poke(context)
		}
	}
}

