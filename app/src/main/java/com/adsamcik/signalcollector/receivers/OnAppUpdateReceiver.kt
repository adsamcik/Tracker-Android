package com.adsamcik.signalcollector.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.adsamcik.signals.tracking.storage.DataStore
import com.adsamcik.signals.base.Assist
import com.adsamcik.signals.base.Preferences
import com.crashlytics.android.Crashlytics

class OnAppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val sp = Preferences.getPref(context)
            val editor = sp.edit()
            Assist.initialize(context)

            if (sp.getInt(Preferences.LAST_VERSION, 0) < 207) {
                DataStore.setCollections(context, 0)
            }

            var currentDataFile = sp.getInt(DataStore.PREF_DATA_FILE_INDEX, -1)
            if (currentDataFile >= 0 && DataStore.exists(context, DataStore.DATA_FILE + currentDataFile)) {
                DataStore.getCurrentDataFile(context)!!.close()
                editor.putInt(DataStore.PREF_DATA_FILE_INDEX, ++currentDataFile)
            }

            try {
                editor.putInt(Preferences.LAST_VERSION, context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
            } catch (e: PackageManager.NameNotFoundException) {
                Crashlytics.logException(e)
            }

            editor.apply()
        }
    }
}
