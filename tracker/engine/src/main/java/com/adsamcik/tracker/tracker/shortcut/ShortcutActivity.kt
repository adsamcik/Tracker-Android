package com.adsamcik.tracker.tracker.shortcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.shortcut.Shortcuts.ShortcutAction

/**
 * ShortcutActivity handles shortcut actions with no UI.
 * Follows north star: ComponentActivity pattern for consistency.
 */
@RequiresApi(25)
class ShortcutActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		intent.let {
			if (it.action == Shortcuts.ACTION) {
				val value = it.getIntExtra(Shortcuts.ACTION_STRING, -1)
				ShortcutAction.values().getOrNull(value)?.let(::onActionReceived)
			}
			finishAffinity()
		}
	}

	private fun onActionReceived(action: ShortcutAction) {
		when (action) {
			ShortcutAction.START_COLLECTION -> {
				TrackerServiceApi.startService(this, true)
			}
			ShortcutAction.STOP_COLLECTION -> {
				TrackerServiceApi.stopService(this)
			}
		}
	}
}
