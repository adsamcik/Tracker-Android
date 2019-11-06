package com.adsamcik.tracker.tracker.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.extension.notificationManager
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver


class TrackerNotificationManager(
		private val context: Context,
		private val isUserInitiatedSession: Boolean
) {
	private var notificationManager: NotificationManager = context.notificationManager

	private var useStyle = getNotificationStylePreference(
			context
	)

	fun createBuilder(): NotificationCompat.Builder {
		return createBuilder(
				context,
				useStyle
		)
				.addTrackerActions()
	}

	private fun NotificationCompat.Builder.addTrackerActions(): NotificationCompat.Builder {
		val resources = context.resources
		val stopIntent = Intent(context, TrackerNotificationReceiver::class.java)

		val notificationAction = if (isUserInitiatedSession) {
			TrackerNotificationReceiver.STOP_TRACKING_ACTION
		} else {
			TrackerNotificationReceiver.LOCK_RECHARGE_ACTION
		}

		stopIntent.putExtra(TrackerNotificationReceiver.ACTION_STRING, notificationAction)
		val stop = PendingIntent.getBroadcast(
				context,
				0,
				stopIntent,
				PendingIntent.FLAG_UPDATE_CURRENT
		)
		if (isUserInitiatedSession) {
			addAction(
					R.drawable.ic_pause_circle_filled_black_24dp,
					resources.getString(R.string.notification_stop),
					stop
			)
		} else {
			addAction(
					R.drawable.ic_battery_alert_black,
					resources.getString(R.string.notification_stop_til_recharge),
					stop
			)

			val stopForMinutesIntent = Intent(context, TrackerNotificationReceiver::class.java)
			stopForMinutesIntent.putExtra(
					TrackerNotificationReceiver.ACTION_STRING,
					TrackerNotificationReceiver.LOCK_TIME_ACTION
			)
			stopForMinutesIntent.putExtra(
					TrackerNotificationReceiver.STOP_MINUTES_EXTRA,
					NotificationComponent.stopForMinutes
			)
			val stopForMinutesAction = PendingIntent.getBroadcast(
					context,
					1,
					stopForMinutesIntent,
					PendingIntent.FLAG_UPDATE_CURRENT
			)
			addAction(
					R.drawable.ic_stop_black_24dp,
					resources.getString(
							R.string.notification_stop_for_minutes,
							NotificationComponent.stopForMinutes
					),
					stopForMinutesAction
			)
		}
		return this
	}

	fun notify(builder: NotificationCompat.Builder) {
		notificationManager.notify(NOTIFICATION_ID, builder.build())
	}

	companion object {
		const val NOTIFICATION_ID = -7643

		private fun getNotificationStylePreference(context: Context): Boolean =
				Preferences.getPref(context).getBooleanRes(
						R.string.settings_notification_styled_key,
						R.string.settings_notification_styled_default
				)

		private fun createBuilder(context: Context, useStyle: Boolean): NotificationCompat.Builder {
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
					.setContentIntent(TaskStackBuilder.create(context).run {
						addNextIntentWithParentStack(intent)
						getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
					}).also {
						if (useStyle) {
							it.setColor(StyleManager.styleData.backgroundColor(isInverted = false))
									.setColorized(true)
						}
					}
		}

		fun getForegroundNotification(context: Context): Notification {
			return createBuilder(
					context,
					getNotificationStylePreference(
							context
					)
			)
					.setContentTitle(context.getString(R.string.notification_starting))
					.build()
		}
	}
}
