package com.adsamcik.signalcollector.shortcut

import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.misc.extension.startForegroundService
import com.adsamcik.signalcollector.common.misc.extension.stopService
import com.adsamcik.signalcollector.shortcut.Shortcuts.ShortcutAction
import com.adsamcik.signalcollector.tracker.service.TrackerService

/**
 * ShortcutActivity is activity that handles shortcut actions, so no UI is shown.
 */
@RequiresApi(25)
class ShortcutActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		Reporter.initialize(this)
		intent.let {
			if (it.action == Shortcuts.ACTION) {
				val value = it.getIntExtra(Shortcuts.ACTION_STRING, -1)
				if (value < 0 || value >= ShortcutAction.values().size) {
					Reporter.report(Throwable("Invalid value $value"))
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
					putExtra(TrackerService.ARG_IS_USER_INITIATED, true)
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
