package com.adsamcik.signalcollector.misc.shortcut

import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.signalcollector.misc.extension.startForegroundService
import com.adsamcik.signalcollector.misc.extension.stopService
import com.adsamcik.signalcollector.misc.shortcut.Shortcuts.ShortcutAction
import com.adsamcik.signalcollector.tracker.service.TrackerService
import com.crashlytics.android.Crashlytics

/**
 * ShortcutActivity is activity that handles shortcut actions, so no UI is shown.
 */
@RequiresApi(25)
class ShortcutActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		intent.let {
			if (it.action == Shortcuts.ACTION) {
				val value = it.getIntExtra(Shortcuts.ACTION_STRING, -1)
				if (value < 0 || value >= ShortcutAction.values().size) {
					Crashlytics.logException(Throwable("Invalid value $value"))
				} else {
					onActionReceived(ShortcutAction.values()[value])
				}
			}
			finishAffinity()
		}
	}

	private fun onActionReceived(action: ShortcutAction) {
		when (action) {
			Shortcuts.ShortcutAction.START_COLLECTION -> {
				startForegroundService<TrackerService> {
					putExtra("backTrack", false)
				}
			}
			Shortcuts.ShortcutAction.STOP_COLLECTION -> {
				if (TrackerService.isServiceRunning.value) {
					stopService<TrackerService>()
				}
			}
		}
	}
}
