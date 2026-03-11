package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface BootReceiverEntryPoint {
		fun lockManager(): LockManager
		@ApplicationScope fun appScope(): CoroutineScope
	}

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
			val entryPoint = EntryPointAccessors.fromApplication(
				context.applicationContext,
				BootReceiverEntryPoint::class.java
			)
			val pendingResult = goAsync()
			entryPoint.appScope().launch {
				try {
					entryPoint.lockManager().initializeFromPersistence(context)
					ActivityWatcherService.poke(context)
				} finally {
					pendingResult.finish()
				}
			}
		}
	}
}

