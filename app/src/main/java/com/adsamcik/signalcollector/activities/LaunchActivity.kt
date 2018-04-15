package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.extensions.jobScheduler
import com.adsamcik.signalcollector.extensions.startActivity
import com.adsamcik.signalcollector.jobs.UploadJobService
import com.adsamcik.signalcollector.notifications.NotificationChannels
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.adsamcik.signalcollector.utility.Shortcuts
import com.adsamcik.signalcollector.utility.TrackingLocker
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.iid.FirebaseInstanceId


/**
 * LaunchActivity is activity that should always be called first when user should see the layout
 * Not only does it allow for easy switching of appropriate Activities, but it also shows SplashScreen and initializes basic services
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false)
            val token = FirebaseInstanceId.getInstance().token
            Log.d("Signals", token ?: "null token")
        }

        val scheduler = jobScheduler
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
