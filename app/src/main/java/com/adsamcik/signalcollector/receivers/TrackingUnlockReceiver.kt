package com.adsamcik.signalcollector.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.utility.TrackingLocker

class TrackingUnlockReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackingLocker.poke(context)
    }

}
