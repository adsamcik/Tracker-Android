package com.adsamcik.signalcollector.activities

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.utility.NotYetImplementedException
import com.adsamcik.signalcollector.utility.Shortcuts
import com.adsamcik.signalcollector.utility.Shortcuts.ShortcutType
import com.google.firebase.crash.FirebaseCrash

class ShortcutActivity : Activity() {

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent.action == Shortcuts.ACTION) {
            val value = intent.getIntExtra(Shortcuts.ACTION_STRING, -1)
            if (value >= 0 && value < ShortcutType.values().size) {
                val type = ShortcutType.values()[value]
                val serviceIntent = Intent(this, TrackerService::class.java)

                when (type) {
                    Shortcuts.ShortcutType.START_COLLECTION -> {
                        serviceIntent.putExtra("backTrack", false)
                        startService(serviceIntent)
                    }
                    Shortcuts.ShortcutType.STOP_COLLECTION -> if (TrackerService.isRunning)
                        stopService(serviceIntent)
                    else -> throw NotYetImplementedException(type.name + " is not implemented.")
                }
            } else {
                FirebaseCrash.report(Throwable("Invalid value " + value))
            }
        }
        finishAffinity()
    }
}
