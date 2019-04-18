package com.adsamcik.signalcollector.misc.shortcut

import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.signalcollector.misc.extension.startForegroundService
import com.adsamcik.signalcollector.misc.extension.stopService
import com.adsamcik.signalcollector.misc.shortcut.Shortcuts.ShortcutType
import com.adsamcik.signalcollector.tracker.service.TrackerService
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

				when (ShortcutType.values()[value]) {
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
