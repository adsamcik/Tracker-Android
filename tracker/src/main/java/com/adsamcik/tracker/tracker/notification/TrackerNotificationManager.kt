package com.adsamcik.tracker.tracker.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.extension.notificationManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.consumer.post.NotificationComponent
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver

/**
 * Notification manager for tracker service and related modules.
 */
class TrackerNotificationManager(
    private val context: Context,
    private val isUserInitiatedSession: Boolean,
    notificationStyled: Boolean = true,
) {
	private var notificationManager: NotificationManager = context.notificationManager

	private var useStyle = notificationStyled

	fun createBuilder(): NotificationCompat.Builder {
		return createBuilder(
			context,
			useStyle
		).addTrackerActions()
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
			PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE)
		)
		if (isUserInitiatedSession) {
			addAction(
				com.adsamcik.tracker.shared.base.R.drawable.ic_pause_circle_filled_black_24dp,
				resources.getString(R.string.notification_stop),
				stop
			)
		} else {
			addAction(
				com.adsamcik.tracker.shared.base.R.drawable.ic_battery_alert_black,
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
				PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE)
			)
			addAction(
				com.adsamcik.tracker.shared.base.R.drawable.ic_stop_black_24dp,
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
		const val NOTIFICATION_ID: Int = -7643

		fun getForegroundNotification(
			context: Context,
			notificationStyled: Boolean = true,
		): Notification {
			return createBuilder(
				context,
				notificationStyled
			)
				.setContentTitle(context.getString(R.string.notification_starting))
				.build()
		}

		private fun createBuilder(context: Context, useStyle: Boolean): NotificationCompat.Builder {
			val resources = context.resources
			val intent =
				requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
			return NotificationCompat.Builder(
				context,
				resources.getString(com.adsamcik.tracker.shared.base.R.string.channel_track_id)
			)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setSmallIcon(com.adsamcik.tracker.shared.base.R.drawable.ic_signals) // the done icon
				.setTicker(resources.getString(R.string.notification_tracker_active_ticker)) // the done text
				.setWhen(Time.nowMillis) // the time stamp
				.setOngoing(true)
				.setContentIntent(TaskStackBuilder.create(context).run {
					addNextIntentWithParentStack(intent)
					getPendingIntent(
						0,
						PendingIntent.FLAG_UPDATE_CURRENT.or(PendingIntent.FLAG_IMMUTABLE)
					)
				}).also {
					if (useStyle) {
						// Use Material 3 colors based on system theme instead of legacy StyleManager
						val isDarkTheme = context.resources.configuration.uiMode and 
							Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
						val backgroundColor = if (isDarkTheme) {
							0xFF1C1B1F.toInt() // Material 3 dark surface
						} else {
							0xFFFEF7FF.toInt() // Material 3 light surface  
						}
						it.setColor(backgroundColor)
							.setColorized(true)
					}
				}
		}
	}
}
