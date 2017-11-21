package com.adsamcik.signalcollector.utility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.support.annotation.RequiresApi
import android.support.annotation.StringRes

import com.adsamcik.signalcollector.R

@RequiresApi(26)
object NotificationTools {

    fun prepareChannels(context: Context) {
        createChannel(context, R.string.channel_track_id, R.string.channel_track_name, R.string.channel_track_description, true, android.app.NotificationManager.IMPORTANCE_LOW)
        createChannel(context, R.string.channel_upload_id, R.string.channel_upload_name, R.string.channel_upload_description, false, NotificationManager.IMPORTANCE_HIGH)
        createChannel(context, R.string.channel_other_id, R.string.channel_other_name, R.string.channel_other_description, true, android.app.NotificationManager.IMPORTANCE_LOW)
        createChannel(context, R.string.channel_challenges_id, R.string.channel_challenges_name, R.string.channel_challenges_description, true, NotificationManager.IMPORTANCE_HIGH)
        createChannel(context, R.string.channel_activity_watcher_id, R.string.channel_activity_watcher_name, R.string.channel_activity_watcher_description, false, NotificationManager.IMPORTANCE_NONE)
    }

    private fun createChannel(context: Context, @StringRes idId: Int, @StringRes nameId: Int, @StringRes descriptionId: Int, useVibration: Boolean, importance: Int) {
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val mChannel = NotificationChannel(context.getString(idId), context.getString(nameId), importance)
        // Configure the notification channel.
        mChannel.description = context.getString(descriptionId)
        mChannel.enableLights(true)
        mChannel.lightColor = Color.GREEN
        mChannel.enableVibration(useVibration)
        mChannel.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
        mNotificationManager.createNotificationChannel(mChannel)
    }
}
