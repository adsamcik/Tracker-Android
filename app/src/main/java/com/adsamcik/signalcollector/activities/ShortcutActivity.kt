package com.adsamcik.signalcollector.activities

import android.os.Bundle
import android.support.annotation.RequiresApi
import android.support.v7.app.AppCompatActivity
import com.adsamcik.signalcollector.extensions.startForegroundService
import com.adsamcik.signalcollector.extensions.stopService
import com.adsamcik.signalcollector.services.TrackerService
import com.adsamcik.signalcollector.utility.Shortcuts
import com.adsamcik.signalcollector.utility.Shortcuts.ShortcutType
import com.crashlytics.android.Crashlytics

/**
 * ShortcutActivity is activity that handles shortcut actions, so no UI is shown.
 */
@RequiresApi(25)
class ShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        if (intent.action == Shortcuts.ACTION) {
            val value = intent.getIntExtra(Shortcuts.ACTION_STRING, -1)
            if (value >= 0 && value < ShortcutType.values().size) {
                val type = ShortcutType.values()[value]

                when (type) {
                    Shortcuts.ShortcutType.START_COLLECTION -> {
                        startForegroundService<TrackerService> {
                            putExtra("backTrack", false)
                        }
                    }
                    Shortcuts.ShortcutType.STOP_COLLECTION -> {
                        if (TrackerService.isServiceRunning.value)
                            stopService<TrackerService>()
                    }
                }
            } else {
                Crashlytics.logException(Throwable("Invalid value $value"))
            }
        }
        finishAffinity()
    }
}
