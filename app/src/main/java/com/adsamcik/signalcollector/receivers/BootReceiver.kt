package com.adsamcik.signalcollector.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.BuildConfig
import com.adsamcik.signalcollector.activities.MainActivity
import com.adsamcik.signalcollector.services.ActivityService
import com.adsamcik.signalcollector.services.ActivityWakerService
import com.crashlytics.android.Crashlytics
import io.fabric.sdk.android.Fabric

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (!BuildConfig.DEBUG)
                Fabric.with(context, Crashlytics())
            ActivityWakerService.poke(context)
            ActivityService.requestAutoTracking(context, MainActivity::class.java)
        }
    }
}
