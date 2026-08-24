package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.extension.appVersion
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receiver that is subscribed to update event so some actions can be performed and services are restored
 */
class OnAppUpdateReceiver : BroadcastReceiver() {
	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface AppUpdateEntryPoint {
		fun bootTrackingRecoveryScheduler(): BootTrackingRecoveryScheduler
	}

	override fun onReceive(context: Context, intent: Intent) {
		val action = intent.action
		if (action != null && action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			val recoveryScheduler = EntryPointAccessors.fromApplication(
				context.applicationContext,
				AppUpdateEntryPoint::class.java,
			).bootTrackingRecoveryScheduler()
			val pendingResult = goAsync()
			CoroutineScope(DefaultDispatchersProvider.default).launch {
				try {
					withTimeoutOrNull(APP_UPDATE_MAINTENANCE_TIMEOUT_MS) {
						handleAppUpdate(context)
					}
				} finally {
					// Reuse the boot/upgrade recovery owner. The worker, not this receiver,
					// opens startup state and re-arms automatic Activity control.
					recoveryScheduler.enqueue()
					pendingResult.finish()
				}
			}
		}
	}
	
	private suspend fun handleAppUpdate(context: Context) {
		val prefs = Preferences(context)
		val keyLastVersion = context.getString(R.string.key_last_app_version)
		val lastVersion = prefs.fetchLong(keyLastVersion, 0L)

		if (lastVersion < 359) {
			prefs.edit {
				remove("goalWeekReached")
				remove("goalDayReached")
			}
		}

		val version = context.appVersion()
		prefs.edit { setLong(keyLastVersion, version) }
	}

	private companion object {
		const val APP_UPDATE_MAINTENANCE_TIMEOUT_MS = 8_000L
	}
}
