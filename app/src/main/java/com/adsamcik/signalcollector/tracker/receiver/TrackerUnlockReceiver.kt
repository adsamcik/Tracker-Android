package com.adsamcik.signalcollector.tracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.adsamcik.signalcollector.tracker.TrackerLocker

class TrackerUnlockReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackerLocker.poke(context)
    }

}
