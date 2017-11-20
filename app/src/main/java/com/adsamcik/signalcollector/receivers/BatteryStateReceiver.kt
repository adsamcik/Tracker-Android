package com.adsamcik.signalcollector.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.utility.Preferences

class BatteryStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null) {
            when (action) {
                Intent.ACTION_BATTERY_LOW -> {
                    Preferences.stopTillRecharge(context)
                    if (TrackerService.isRunning)
                        context.stopService(Intent(context, TrackerService::class.java))
                }
            }
        }
    }
}
