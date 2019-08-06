package com.adsamcik.signalcollector.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.api.ActivityRecognitionApi
import com.adsamcik.signalcollector.common.database.AppDatabase
import com.adsamcik.signalcollector.common.extension.appVersion
import com.adsamcik.signalcollector.common.extension.forEachIf
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.ActivityWatcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Receiver that is subscribed to update event so some actions can be performed and services are restored
 */
class OnAppUpdateReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val action = intent.action
		if (action != null && action == Intent.ACTION_MY_PACKAGE_REPLACED) {

			Preferences.getPref(context).edit {
				val keyLastVersion = context.getString(R.string.key_last_app_version)
				val lastVersion = getLong(keyLastVersion)

				if (lastVersion < 311) {
					ActivityRecognitionApi.rerunRecognitionForAll(context)
				}

				val version = context.appVersion()
				this@edit.setLong(keyLastVersion, version)
			}

			TrackerLocker.initializeFromPersistence(context)
		}
	}
}
