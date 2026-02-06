package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.app.Application
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
			val app = context.applicationContext as Application
			val appGraph = app.appGraph
			val pendingResult = goAsync()
			appGraph.appScope.launch {
				try {
					appGraph.lockManager.initializeFromPersistence(context)
					ActivityWatcherService.poke(context)
				} finally {
					pendingResult.finish()
				}
			}
		}
	}
}

