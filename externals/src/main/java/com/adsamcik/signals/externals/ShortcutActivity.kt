package com.adsamcik.signals.externals

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.annotation.RequiresApi
import com.crashlytics.android.Crashlytics

class ShortcutActivity : Activity() {

    @RequiresApi(api = Build.VERSION_CODES.N_MR1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent.action == Shortcuts.ACTION) {
            val value = intent.getIntExtra(Shortcuts.ACTION_STRING, -1)
            if (value >= 0 && value < Shortcuts.ShortcutType.values().size) {
                val type = Shortcuts.ShortcutType.values()[value]
                val serviceIntent = Intent()
                serviceIntent.component = ComponentName(packageName, packageName + ".tracking.services.TrackerService")

                when (type) {
                    Shortcuts.ShortcutType.START_COLLECTION -> {
                        serviceIntent.putExtra("backTrack", false)
                        startService(serviceIntent)
                    }
                    Shortcuts.ShortcutType.STOP_COLLECTION -> stopService(serviceIntent)
                }
            } else {
                Crashlytics.logException(Throwable("Invalid value " + value))
            }
        }
        finishAffinity()
    }
}
