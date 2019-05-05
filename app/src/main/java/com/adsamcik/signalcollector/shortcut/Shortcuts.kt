package com.adsamcik.signalcollector.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.misc.extension.shortcutManager
import com.adsamcik.signalcollector.tracker.service.TrackerService
import java.util.*

/**
 * Singleton which handles Shortcut creation on API 25 and newer
 */
@RequiresApi(25)
object Shortcuts {
	const val TRACKING_ID: String = "Tracking"
	const val ACTION: String = "com.adsamcik.signalcollector.SHORTCUT"
	const val ACTION_STRING: String = "ShortcutAction"

	var initialized = false

	/**
	 * Initializes shortcuts
	 *
	 * @param context context
	 */
	fun initializeShortcuts(context: Context) {
		if (initialized) return
		initialized = true

		val shortcutManager = context.getSystemService(ShortcutManager::class.java)
		val shortcuts = ArrayList<ShortcutInfo>(1)
		if (!TrackerService.isServiceRunning.value) {
			shortcuts.add(createShortcut(context,
					TRACKING_ID,
					R.string.shortcut_start_tracking,
					R.string.shortcut_start_tracking_long,
					R.drawable.ic_play_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.START_COLLECTION))
		} else {
			shortcuts.add(createShortcut(context,
					TRACKING_ID,
					R.string.shortcut_stop_tracking,
					R.string.shortcut_stop_tracking_long,
					R.drawable.ic_pause_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.STOP_COLLECTION))
		}

		shortcutManager.dynamicShortcuts = shortcuts
	}


	private fun createShortcut(context: Context,
	                           id: String,
	                           @StringRes shortLabelRes: Int,
	                           @StringRes longLabelRes: Int,
	                           @DrawableRes iconResource: Int,
	                           action: ShortcutAction): ShortcutInfo {
		with(context) {
			val shortLabel = getString(shortLabelRes)
			val longLabel = getString(longLabelRes)

			return ShortcutInfo.Builder(this, id)
					.setShortLabel(shortLabel)
					.setIcon(Icon.createWithResource(this, iconResource))
					.setIntent(Intent(context, ShortcutActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setAction(ACTION).putExtra(ACTION_STRING, action.ordinal))
					.setLongLabel(longLabel)
					.build()
		}
	}

	/**
	 * Updates shortcuts value
	 *
	 * @param context Context
	 * @param id Shortcut's ID
	 * @param shortLabelRes Short label of the shortcut. The recommended maximum length is 10 characters. [ShortcutInfo.Builder.setShortLabel]
	 * @param longLabelRes Long label of the shortcut. The recommend maximum length is 25 characters. [ShortcutInfo.Builder.setLongLabel]
	 * @param iconResource Drawable resource id for the icon
	 * @param shortcutAction Shortcut type defined in [ShortcutAction]. Action needs to be defined in [ShortcutActivity].
	 */
	fun updateShortcut(context: Context,
	                   id: String,
	                   @StringRes shortLabelRes: Int,
	                   @StringRes longLabelRes: Int,
	                   @DrawableRes iconResource: Int,
	                   shortcutAction: ShortcutAction) {
		initializeShortcuts(context)
		val shortcutManager = context.shortcutManager
		val shortcuts = shortcutManager.dynamicShortcuts

		val index = shortcuts.indexOfFirst { it.id == id }

		if (index != -1)
			shortcuts[index] = createShortcut(context, id, shortLabelRes, longLabelRes, iconResource, shortcutAction)

		shortcutManager.updateShortcuts(shortcuts)
	}


	/**
	 * Supported shortcut types
	 */
	enum class ShortcutAction {
		START_COLLECTION,
		STOP_COLLECTION
	}
}
