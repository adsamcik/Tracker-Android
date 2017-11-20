package com.adsamcik.signalcollector.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.utility.Preferences

class ChargingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_POWER_CONNECTED)
            Preferences.get(context).edit().putBoolean(Preferences.PREF_STOP_TILL_RECHARGE, false).apply()
    }
}
