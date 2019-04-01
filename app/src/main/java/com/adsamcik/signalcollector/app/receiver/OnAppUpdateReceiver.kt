package com.adsamcik.signalcollector.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.activity.service.ActivityWatcherService
import com.adsamcik.signalcollector.app.activity.LaunchActivity
import com.adsamcik.signalcollector.preference.Preferences
import com.adsamcik.signalcollector.tracker.TrackerLocker
import com.crashlytics.android.Crashlytics

/**
 * Receiver that is subscribed to update event so some actions can be performed and services are restored
 */
class OnAppUpdateReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val action = intent.action
		if (action != null && action == Intent.ACTION_MY_PACKAGE_REPLACED) {

			val sp = Preferences.getPref(context)
			val editor = sp.edit()

			val keyLastVersion = context.getString(R.string.key_last_app_version)
			val lastVersion = sp.getLong(keyLastVersion, 0)

			if (lastVersion < 290) {
				editor.clear()
			}

			/*if (sp.getLong(Preferences.LAST_VERSION, 0) < 277) {
				DataStore.clearAll(context)
				CacheStore.clearAll(context)
			}

			var currentDataFile = sp.getInt(DataStore.PREF_DATA_FILE_INDEX, -1)
			if (currentDataFile >= 0 && DataStore.exists(context, DataStore.DATA_FILE + currentDataFile)) {
				DataStore.getCurrentDataFile(context)!!.close()
				editor.putInt(DataStore.PREF_DATA_FILE_INDEX, ++currentDataFile)
			}*/

			try {
				val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
				@Suppress("DEPRECATION")
				val version = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
				editor.putLong(keyLastVersion, version)
			} catch (e: PackageManager.NameNotFoundException) {
				Crashlytics.logException(e)
			}

			editor.apply()

			TrackerLocker.initializeFromPersistence(context)
			ActivityWatcherService.pokeWithCheck(context)
			ActivityService.requestAutoTracking(context, LaunchActivity::class.java)
		}
	}
}
