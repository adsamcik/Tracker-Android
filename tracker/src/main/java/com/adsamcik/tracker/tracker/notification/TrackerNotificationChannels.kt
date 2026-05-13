package com.adsamcik.tracker.tracker.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.annotation.StringRes
import com.adsamcik.tracker.shared.base.R as BaseR
import com.adsamcik.tracker.shared.base.extension.notificationManager

/** Ensures tracker foreground-service notification channels exist before notifications are posted. */
internal object TrackerNotificationChannels {
    private val VIBRATION_PATTERN = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)

    fun ensureTrackingChannel(context: Context) {
        createChannelIfNeeded(
            context = context,
            idRes = BaseR.string.channel_track_id,
            nameRes = BaseR.string.channel_track_name,
            descriptionRes = BaseR.string.channel_track_description,
            useVibration = true,
            importance = NotificationManager.IMPORTANCE_LOW,
        )
    }

    fun ensureActivityWatcherChannel(context: Context) {
        createChannelIfNeeded(
            context = context,
            idRes = BaseR.string.channel_activity_watcher_id,
            nameRes = BaseR.string.channel_activity_watcher_name,
            descriptionRes = BaseR.string.channel_activity_watcher_description,
            useVibration = false,
            importance = NotificationManager.IMPORTANCE_LOW,
        )
    }

    fun ensureForegroundServiceChannels(context: Context) {
        ensureTrackingChannel(context)
        ensureActivityWatcherChannel(context)
    }

    private fun createChannelIfNeeded(
        context: Context,
        @StringRes idRes: Int,
        @StringRes nameRes: Int,
        @StringRes descriptionRes: Int,
        useVibration: Boolean,
        importance: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channelId = context.getString(idRes)
        val manager = context.notificationManager
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            context.getString(nameRes),
            importance,
        ).apply {
            description = context.getString(descriptionRes)
            enableLights(true)
            lightColor = Color.GREEN
            enableVibration(useVibration)
            if (useVibration) vibrationPattern = VIBRATION_PATTERN
        }
        manager.createNotificationChannel(channel)
    }
}
