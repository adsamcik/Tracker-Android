package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signals.externals.ChannelTools
import com.adsamcik.signals.externals.Shortcuts
import com.adsamcik.signals.tracking.services.TrackerService
import com.adsamcik.signals.tracking.services.UploadJobService
import com.adsamcik.signals.base.FirebaseAssist
import com.adsamcik.signals.base.Preferences
import com.adsamcik.signals.base.test.useMock
import com.crashlytics.android.Crashlytics
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.iid.FirebaseInstanceId
import io.fabric.sdk.android.Fabric


class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Preferences.setTheme(this)
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this);

        if (BuildConfig.DEBUG) {
            FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false)
            val token = FirebaseInstanceId.getInstance().token
            Log.d("Signals", token ?: "null token")
        } else
            Fabric.with(this, Crashlytics())

        val scheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val sp = Preferences.getPref(this)
        if (sp.getInt(Preferences.LAST_VERSION, 0) <= 138) {
            val editor = sp.edit()
            FirebaseAssist.updateValue(this, FirebaseAssist.autoTrackingString, resources.getStringArray(R.array.background_tracking_options)[Preferences.getPref(this).getInt(Preferences.PREF_AUTO_TRACKING, Preferences.DEFAULT_AUTO_TRACKING)])
            FirebaseAssist.updateValue(this, FirebaseAssist.autoUploadString, resources.getStringArray(R.array.automatic_upload_options)[Preferences.getPref(this).getInt(Preferences.PREF_AUTO_UPLOAD, Preferences.DEFAULT_AUTO_UPLOAD)])
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

        if (sp.getBoolean(Preferences.PREF_HAS_BEEN_LAUNCHED, false) || useMock)
            startActivity(Intent(this, StandardUIActivity::class.java))
        else
            startActivity(Intent(this, IntroActivity::class.java))

        if (Build.VERSION.SDK_INT >= 25)
            Shortcuts.updateShortcuts(this, TrackerService.isRunning)

        if (Build.VERSION.SDK_INT >= 26)
            ChannelTools.prepareChannels(this)

        ActivityWakerService.poke(this)

        overridePendingTransition(0, 0)
        finish()
    }
}
