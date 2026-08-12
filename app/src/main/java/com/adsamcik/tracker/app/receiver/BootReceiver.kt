package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.app.startup.LegacyDatabaseStartupResult
import com.adsamcik.tracker.app.startup.LegacyDatabaseUpgradeCoordinator
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
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
		fun trackingStartupGuard(): TrackingStartupGuard
		fun legacyDatabaseUpgradeCoordinator(): LegacyDatabaseUpgradeCoordinator
		@ApplicationScope fun appScope(): CoroutineScope
	}

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
			val entryPoint = EntryPointAccessors.fromApplication(
				context.applicationContext,
				BootReceiverEntryPoint::class.java
			)
			if (entryPoint.trackingStartupGuard().isAutoRecoverySuppressed(context)) return
			val pendingResult = goAsync()
			entryPoint.appScope().launch {
				try {
					val legacyStartup = entryPoint.legacyDatabaseUpgradeCoordinator().ensureReady()
					if (legacyStartup !is LegacyDatabaseStartupResult.Ready) return@launch
					entryPoint.lockManager().initializeFromPersistence(context)
					// Re-arm background auto-tracking after a reboot WITHOUT starting any
					// foreground service: Android 14+ forbids launching a location FGS from
					// BOOT_COMPLETED. This (re)registers the Google Play activity-recognition
					// subscription (a PendingIntent, not a service); the next movement
					// transition is an allowed exemption that then starts TrackerService.
					// Idempotent — safe even though module init may also call it.
					BackgroundTrackingApi.initialize(context)
				} finally {
					pendingResult.finish()
				}
			}
		}
	}
}
