package com.adsamcik.tracker.tracker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.notificationManager
import com.adsamcik.tracker.common.style.StyleManager

class TrackerNotificationManager(private val context: Context) {
	private var notificationManager: NotificationManager = context.notificationManager

	fun createBuilder(): NotificationCompat.Builder {
		val resources = context.resources
		val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))

		return NotificationCompat.Builder(
				context,
				resources.getString(com.adsamcik.tracker.common.R.string.channel_track_id)
		)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setSmallIcon(R.drawable.ic_signals)  // the done icon
				.setTicker(resources.getString(R.string.notification_tracker_active_ticker))  // the done text
				.setWhen(Time.nowMillis)  // the time stamp
				.setOngoing(true)
				.setColor(StyleManager.styleData.backgroundColor(isInverted = false))
				.setContentIntent(TaskStackBuilder.create(context).run {
					addNextIntentWithParentStack(intent)
					getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
				})
	}

	fun notify(builder: NotificationCompat.Builder) {
		notificationManager.notify(NOTIFICATION_ID, builder.build())
	}

	companion object {
		const val NOTIFICATION_ID = -7643
	}
}
