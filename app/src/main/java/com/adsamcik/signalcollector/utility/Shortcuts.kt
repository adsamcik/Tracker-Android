package com.adsamcik.signalcollector.utility

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.support.annotation.DrawableRes
import android.support.annotation.RequiresApi
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.activities.ShortcutActivity
import com.adsamcik.signalcollector.services.TrackerService
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
            shortcuts.add(createShortcut(context, TRACKING_ID, context.getString(R.string.shortcut_start_tracking), context.getString(R.string.shortcut_start_tracking_long), R.drawable.ic_play_circle_filled_black_24dp, ShortcutType.START_COLLECTION))
        else
            shortcuts.add(createShortcut(context, TRACKING_ID, context.getString(R.string.shortcut_stop_tracking), context.getString(R.string.shortcut_stop_tracking_long), R.drawable.ic_pause_circle_filled_black_24dp, ShortcutType.STOP_COLLECTION))

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
     */
    fun updateShortcut(context: Context, id: String, shortLabel: String, longLabel: String?, @DrawableRes iconResource: Int, action: ShortcutType) {
        initializeShortcuts(context)
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)!!
        val shortcuts = shortcutManager.dynamicShortcuts
        for (i in shortcuts.indices) {
            if (shortcuts[i].id == id) {
                shortcuts[i] = createShortcut(context, id, shortLabel, longLabel, iconResource, action)
                break
            }
        }
        shortcutManager.updateShortcuts(shortcuts)
    }


    enum class ShortcutType {
        START_COLLECTION,
        STOP_COLLECTION
    }
}
