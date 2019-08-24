package com.adsamcik.tracker.tracker.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import com.adsamcik.tracker.common.extension.shortcutManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.service.TrackerService
import java.util.*

/**
 * Singleton which handles Shortcut creation on API 25 and newer
 */
@RequiresApi(Build.VERSION_CODES.N_MR1)
object Shortcuts {
	const val TRACKING_ID: String = "Tracking"
	const val ACTION: String = "com.adsamcik.tracker.SHORTCUT"
	const val ACTION_STRING: String = "ShortcutAction"

	private var isInitialized = false

	/**
	 * Initializes shortcuts
	 *
	 * @param context context
	 */
	@Synchronized
	fun initializeShortcuts(context: Context) {
		if (isInitialized) return
		isInitialized = true

		val shortcutManager = context.shortcutManager
		val shortcuts = ArrayList<ShortcutInfo>(1)
		if (!TrackerService.isServiceRunning.value) {
			shortcuts.add(
					createShortcut(
							context,
							ShortcutData(
									TRACKING_ID,
									R.string.shortcut_start_tracking,
									R.string.shortcut_start_tracking_long,
									R.drawable.ic_play_circle_filled_black_24dp,
									ShortcutAction.START_COLLECTION
							)
					)
			)
		} else {
			shortcuts.add(
					createShortcut(
							context,
							ShortcutData(
									TRACKING_ID,
									R.string.shortcut_stop_tracking,
									R.string.shortcut_stop_tracking_long,
									R.drawable.ic_pause_circle_filled_black_24dp,
									ShortcutAction.STOP_COLLECTION
							)
					)
			)
		}

		shortcutManager.dynamicShortcuts = shortcuts
	}


	private fun createShortcut(
			context: Context,
			data: ShortcutData
	): ShortcutInfo {
		with(context) {
			val shortLabel = getString(data.shortLabelRes)
			val longLabel = getString(data.longLabelRes)

			return ShortcutInfo.Builder(this, data.id)
					.setShortLabel(shortLabel)
					.setIcon(Icon.createWithResource(this, data.iconResource))
					.setIntent(
							Intent(context, ShortcutActivity::class.java)
									.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
									.setAction(ACTION)
									.putExtra(ACTION_STRING, data.shortcutAction.ordinal)
					)
					.setLongLabel(longLabel)
					.build()
		}
	}

	/**
	 * Updates shortcuts value
	 *
	 * @param context Context
	 * @param data Shortcut data
	 */
	fun updateShortcut(
			context: Context,
			data: ShortcutData
	) {
		initializeShortcuts(context)
		val shortcutManager = context.shortcutManager
		val shortcuts = shortcutManager.dynamicShortcuts

		val index = shortcuts.indexOfFirst { it.id == data.id }

		if (index != -1) {
			shortcuts[index] = createShortcut(context, data)
		}

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

