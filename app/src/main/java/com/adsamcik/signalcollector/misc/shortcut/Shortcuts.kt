package com.adsamcik.signalcollector.misc.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.misc.extension.shortcutManager
import com.adsamcik.signalcollector.tracker.service.TrackerService
import java.util.*

/**
 * Singleton which handles Shortcut creation on API 25 and newer
 */
@RequiresApi(25)
object Shortcuts {
    const val TRACKING_ID = "Tracking"
    const val ACTION = "com.adsamcik.signalcollector.SHORTCUT"
    const val ACTION_STRING = "ShortcutAction"

    /**
     * Initializes shortcuts
     *
     * @param context context
     */
    fun initializeShortcuts(context: Context) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val shortcuts = ArrayList<ShortcutInfo>(1)
        if (!TrackerService.isServiceRunning.value)
            shortcuts.add(createShortcut(context, TRACKING_ID, context.getString(R.string.shortcut_start_tracking), context.getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play_circle_filled_black_24dp, Shortcuts.ShortcutType.START_COLLECTION))
        else
            shortcuts.add(createShortcut(context, TRACKING_ID, context.getString(R.string.shortcut_stop_tracking), context.getString(R.string.shortcut_stop_tracking_long), R.drawable.ic_pause_circle_filled_black_24dp, Shortcuts.ShortcutType.STOP_COLLECTION))

        assert(shortcutManager != null)
        shortcutManager!!.dynamicShortcuts = shortcuts
    }


    private fun createShortcut(context: Context, id: String, shortLabel: String, longLabel: String?, @DrawableRes iconResource: Int, action: ShortcutType): ShortcutInfo {
        val shortcutBuilder = ShortcutInfo.Builder(context, id)
                .setShortLabel(shortLabel)
                .setIcon(Icon.createWithResource(context, iconResource))
                .setIntent(Intent(context, ShortcutActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setAction(ACTION).putExtra(ACTION_STRING, action.ordinal))
        if (longLabel != null)
            shortcutBuilder.setLongLabel(longLabel)
        return shortcutBuilder.build()
    }

    /**
     * Updates shortcuts value
     *
     * @param context Context
     * @param id Shortcut's ID
     * @param shortLabel Short label of the shortcut. The recommended maximum length is 10 characters. [ShortcutInfo.Builder.setShortLabel]
     * @param longLabel Long label of the shortcut. The recommend maximum length is 25 characters. [ShortcutInfo.Builder.setLongLabel]
     * @param iconResource Drawable resource id for the icon
     * @param shortcutType Shortcut type defined in [ShortcutType]. Action needs to be defined in [ShortcutActivity].
     */
    fun updateShortcut(context: Context, id: String, shortLabel: String, longLabel: String?, @DrawableRes iconResource: Int, shortcutType: ShortcutType) {
        initializeShortcuts(context)
        val shortcutManager = context.shortcutManager
        val shortcuts = shortcutManager.dynamicShortcuts
        for (i in shortcuts.indices) {
            if (shortcuts[i].id == id) {
                shortcuts[i] = createShortcut(context, id, shortLabel, longLabel, iconResource, shortcutType)
                break
            }
        }
        shortcutManager.updateShortcuts(shortcuts)
    }


    /**
     * Supported shortcut types
     */
    enum class ShortcutType {
        START_COLLECTION,
        STOP_COLLECTION
    }
}
