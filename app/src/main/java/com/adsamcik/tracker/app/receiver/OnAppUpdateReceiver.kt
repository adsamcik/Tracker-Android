package com.adsamcik.tracker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.tracker.R
import com.adsamcik.tracker.shared.base.extension.appVersion
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Receiver that is subscribed to update event so some actions can be performed and services are restored
 */
class OnAppUpdateReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val action = intent.action
		if (action != null && action == Intent.ACTION_MY_PACKAGE_REPLACED) {
			val pendingResult = goAsync()
			CoroutineScope(DefaultDispatchersProvider.default).launch {
				try {
					handleAppUpdate(context)
				} finally {
					pendingResult.finish()
				}
			}
		}
	}
	
	private suspend fun handleAppUpdate(context: Context) {
		val prefs = Preferences.getPref(context)
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
}

