package com.adsamcik.tracker.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes

import com.adsamcik.tracker.shared.base.R

/**
 * Singleton that creates notification channels.
 *
 * Future: Consider plugin system for modules to register their own channels
 * via a central registry to avoid tight coupling.
 */
object NotificationChannels {

	/**
	 * Prepares channels for application
	 */
	fun prepareChannels(context: Context) {
		createChannel(
			context,
			R.string.channel_track_id,
			R.string.channel_track_name,
			R.string.channel_track_description,
			true,
			NotificationManager.IMPORTANCE_LOW,
		)
		createChannel(
			context,
			R.string.channel_other_id,
			R.string.channel_other_name,
			R.string.channel_other_description,
			true,
			NotificationManager.IMPORTANCE_LOW,
		)
		createChannel(
			context,
			R.string.channel_achievements_id,
			R.string.channel_achievements_name,
			R.string.channel_achievements_description,
			true,
			NotificationManager.IMPORTANCE_HIGH,
		)
		createChannel(
			context,
			R.string.channel_activity_watcher_id,
			R.string.channel_activity_watcher_name,
			R.string.channel_activity_watcher_description,
			false,
			NotificationManager.IMPORTANCE_LOW,
		)
		createChannel(
			context,
			R.string.channel_goals_id,
			R.string.channel_goals_name,
			R.string.channel_goals_description,
			false,
			NotificationManager.IMPORTANCE_DEFAULT,
		)
	}

	private fun createChannel(
		context: Context,
		@StringRes idId: Int,
		@StringRes nameId: Int,
		@StringRes descriptionId: Int,
		useVibration: Boolean,
		importance: Int,
	) {
		val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		val mChannel = NotificationChannel(
			context.getString(idId),
			context.getString(nameId),
			importance,
		)
		// Configure the notification channel.
		mChannel.description = context.getString(descriptionId)
		mChannel.enableLights(true)
		mChannel.lightColor = Color.GREEN
		mChannel.enableVibration(useVibration)
		mChannel.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
		mNotificationManager.createNotificationChannel(mChannel)
	}
}
