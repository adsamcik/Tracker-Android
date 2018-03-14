package com.adsamcik.signalcollector.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.activities.LaunchActivity
import com.adsamcik.signalcollector.services.ActivityService
import com.adsamcik.signalcollector.services.ActivityWakerService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ActivityWakerService.poke(context)
            ActivityService.requestAutoTracking(context, LaunchActivity::class.java)
        }
    }
}
