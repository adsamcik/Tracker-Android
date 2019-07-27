package com.adsamcik.signalcollector.tracker.component.post

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.app.activity.MainActivity
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.extension.formatDistance
import com.adsamcik.signalcollector.common.extension.formatSpeed
import com.adsamcik.signalcollector.common.extension.notificationManager
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.component.PostTrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData
import com.adsamcik.signalcollector.tracker.receiver.TrackerNotificationReceiver
import com.adsamcik.signalcollector.tracker.service.TrackerService
import java.math.RoundingMode
import java.text.DecimalFormat

class NotificationComponent : PostTrackerComponent {
	private var notificationManager: NotificationManager? = null

	private val requireNotificationManager: NotificationManager
		get() = notificationManager
				?: throw NullPointerException("Notification manager must be initialized")

	override suspend fun onDisable(context: Context) {
		notificationManager = null
	}

	override suspend fun onEnable(context: Context) {
		notificationManager?.cancel(NOTIFICATION_ID)
		notificationManager = context.notificationManager
	}

	override fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData) {
		notify(generateNotification(context, location, collectionData))
	}

	fun onLocationDataChange(context: Context, location: Location?) {
		notify(generateNotification(context, location, null))
	}

	fun foregroundServiceNotification(context: Context): Pair<Int, Notification> {
		return NOTIFICATION_ID to generateNotification(context)
	}

	private fun notify(notification: Notification) = requireNotificationManager.notify(NOTIFICATION_ID, notification)

	private fun generateNotification(context: Context, location: Location? = null, data: CollectionData? = null): Notification {
		val resources = context.resources
		val intent = Intent(context, MainActivity::class.java)

		val builder = NotificationCompat.Builder(context, resources.getString(R.string.channel_track_id))
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setSmallIcon(R.drawable.ic_signals)  // the done icon
				.setTicker(resources.getString(R.string.notification_tracker_active_ticker))  // the done text
				.setWhen(Time.nowMillis)  // the time stamp
				.setOngoing(true)
				.setColor(ContextCompat.getColor(context, R.color.color_accent))
				.setContentIntent(TaskStackBuilder.create(context).run {
					addNextIntentWithParentStack(intent)
					getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
				})

		when {
			location == null -> builder.setContentTitle(resources.getString(R.string.notification_looking_for_gps))
			//todo add notification text
			data == null -> {
			}
			else -> {
				//todo improve title
				builder.setContentTitle(resources.getString(R.string.notification_tracking_active))
				builder.setStyle(NotificationCompat.BigTextStyle().bigText(buildNotificationText(context, location, data)))
			}
		}


		val trackingSessionInfo = TrackerService.sessionInfo.value

		if (trackingSessionInfo != null) {
			val stopIntent = Intent(context, TrackerNotificationReceiver::class.java)

			val notificationAction = if (trackingSessionInfo.isInitiatedByUser) {
				TrackerNotificationReceiver.STOP_TRACKING_ACTION
			} else {
				TrackerNotificationReceiver.LOCK_RECHARGE_ACTION
			}

			stopIntent.putExtra(TrackerNotificationReceiver.ACTION_STRING, notificationAction)
			val stop = PendingIntent.getBroadcast(context, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
			if (trackingSessionInfo.isInitiatedByUser) {
				builder.addAction(R.drawable.ic_pause_circle_filled_black_24dp, resources.getString(R.string.notification_stop), stop)
			} else {
				builder.addAction(R.drawable.ic_battery_alert_black, resources.getString(R.string.notification_stop_til_recharge), stop)

				val stopForMinutesIntent = Intent(context, TrackerNotificationReceiver::class.java)
				stopForMinutesIntent.putExtra(TrackerNotificationReceiver.ACTION_STRING, TrackerNotificationReceiver.STOP_MINUTES_EXTRA)
				stopForMinutesIntent.putExtra(TrackerNotificationReceiver.STOP_MINUTES_EXTRA, stopForMinutes)
				val stopForMinutesAction = PendingIntent.getBroadcast(context, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT)
				builder.addAction(R.drawable.ic_stop_black_24dp, resources.getString(R.string.notification_stop_for_minutes, stopForMinutes), stopForMinutesAction)
			}
		}

		return builder.build()
	}

	private fun buildNotificationText(context: Context, location: Location, d: CollectionData): String {
		val resources = context.resources
		val sb = StringBuilder()
		val df = DecimalFormat.getNumberInstance()
		df.roundingMode = RoundingMode.HALF_UP

		val lengthSystem = Preferences.getLengthSystem(context)
		val delimiter = ", "

		if (location.hasSpeed()) {
			sb.append(resources.formatSpeed(context, location.speed.toDouble(), 1))
					.append(delimiter)
		}

		sb.append(resources.getString(R.string.info_altitude, resources.formatDistance(location.altitude, 2, lengthSystem)))
				.append(delimiter)

		val activity = d.activity
		if (activity != null) {
			sb.append(resources.getString(R.string.notification_activity,
					activity.getGroupedActivityName(context))).append(delimiter)
		}

		val wifi = d.wifi
		if (wifi != null) {
			sb.append(resources.getString(R.string.notification_wifi, wifi.inRange.size)).append(delimiter)
		}

		val cell = d.cell
		if (cell != null && cell.registeredCells.isNotEmpty()) {
			val mainCell = cell.registeredCells.first()
			sb
					.append(resources.getString(R.string.notification_cell_current, mainCell.type.name, mainCell.dbm))
					.append(' ')
					.append(resources.getQuantityString(R.plurals.notification_cell_count, cell.totalCount, cell.totalCount))
					.append(delimiter)
		}

		sb.setLength(sb.length - 2)

		return sb.toString()
	}

	companion object {
		const val NOTIFICATION_ID = -7643
		const val stopForMinutes = 30
	}

}