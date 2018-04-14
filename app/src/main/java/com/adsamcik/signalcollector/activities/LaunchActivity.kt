package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.app.job.JobScheduler
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.extensions.startActivity
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.notifications.NotificationChannels
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.utility.FirebaseAssist
import com.adsamcik.signalcollector.utility.Preferences
import com.adsamcik.signalcollector.utility.Shortcuts
import com.adsamcik.signalcollector.utility.TrackingLocker
import com.crashlytics.android.Crashlytics
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.iid.FirebaseInstanceId


class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false)
            val token = FirebaseInstanceId.getInstance().token
            Log.d("Signals", token ?: "null token")
        }

        val scheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val sp = Preferences.getPref(this)
        if (sp.getInt(Preferences.LAST_VERSION, 0) <= 138) {
            val editor = sp.edit()
            FirebaseAssist.updateValue(this, FirebaseAssist.uploadNotificationString, java.lang.Boolean.toString(Preferences.getPref(this).getBoolean(Preferences.PREF_UPLOAD_NOTIFICATIONS_ENABLED, true)))

            editor.remove(Preferences.PREF_SCHEDULED_UPLOAD)

            try {
                editor.putInt(Preferences.LAST_VERSION, packageManager.getPackageInfo(packageName, 0).versionCode)
            } catch (e: PackageManager.NameNotFoundException) {
                Crashlytics.logException(e)
            }

            editor.apply()

            scheduler.cancelAll()
        } else {
            val uss = UploadJobService.getUploadScheduled(this)
            if (uss != UploadJobService.UploadScheduleSource.NONE) {
                val jobs = scheduler.allPendingJobs

                val found = jobs.count { it.service.className == "UploadJobService" }
                if (found > 1) {
                    scheduler.cancelAll()
                    UploadJobService.requestUpload(this, uss)
                } else if (found == 0) {
                    UploadJobService.requestUpload(this, uss)
                }
            }
        }

        startActivity<MainActivity> { }

        if (Build.VERSION.SDK_INT >= 25)
            Shortcuts.initializeShortcuts(this)

        if (Build.VERSION.SDK_INT >= 26)
            NotificationChannels.prepareChannels(this)

        ActivityWakerService.pokeWithCheck(this)

        TrackingLocker.initializeFromPersistence(this)

        overridePendingTransition(0, 0)
        finish()
    }
}
