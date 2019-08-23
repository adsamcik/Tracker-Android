package com.adsamcik.tracker.tracker.component.consumer.post

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.extension.formatDistance
import com.adsamcik.tracker.common.extension.formatSpeed
import com.adsamcik.tracker.common.extension.notificationManager
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.common.style.StyleManager
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver
import com.adsamcik.tracker.tracker.service.TrackerService
import java.math.RoundingMode
import java.text.DecimalFormat

internal class NotificationComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

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

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		val location = tempData.tryGetLocation()
		notify(generateNotification(context, location, collectionData))
	}

	fun onLocationDataChange(context: Context, location: Location?) {
		notify(generateNotification(context, location, null))
	}

	fun onError(context: Context, @StringRes textRes: Int) {
		val builder = prepareNotificationBase(context)
		builder.setContentTitle(context.getString(textRes))
		notify(builder.build())
	}

	fun foregroundServiceNotification(context: Context): Pair<Int, Notification> {
		return NOTIFICATION_ID to generateNotification(context)
	}

	private fun notify(notification: Notification) = requireNotificationManager.notify(
			NOTIFICATION_ID,
			notification
	)

	private fun generateNotification(
			context: Context,
			location: Location? = null,
			data: CollectionData? = null
	): Notification {
		val builder = prepareNotificationBase(context)

		val resources = context.resources

		when {
			location == null -> builder.setContentTitle(resources.getString(R.string.notification_looking_for_gps))
			//todo add notification text
			data == null -> {
			}
			else -> {
				//todo improve title
				builder.setContentTitle(resources.getString(R.string.notification_tracking_active))
				builder.setStyle(
						NotificationCompat.BigTextStyle().bigText(
								buildNotificationText(
										context,
										location,
										data
								)
						)
				)
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
			val stop = PendingIntent.getBroadcast(
					context,
					0,
					stopIntent,
					PendingIntent.FLAG_UPDATE_CURRENT
			)
			if (trackingSessionInfo.isInitiatedByUser) {
				builder.addAction(
						R.drawable.ic_pause_circle_filled_black_24dp,
						resources.getString(R.string.notification_stop), stop
				)
			} else {
				builder.addAction(
						R.drawable.ic_battery_alert_black,
						resources.getString(R.string.notification_stop_til_recharge), stop
				)

				val stopForMinutesIntent = Intent(context, TrackerNotificationReceiver::class.java)
				stopForMinutesIntent.putExtra(
						TrackerNotificationReceiver.ACTION_STRING,
						TrackerNotificationReceiver.STOP_MINUTES_EXTRA
				)
				stopForMinutesIntent.putExtra(
						TrackerNotificationReceiver.STOP_MINUTES_EXTRA,
						stopForMinutes
				)
				val stopForMinutesAction = PendingIntent.getBroadcast(
						context, 1, stopIntent,
						PendingIntent.FLAG_UPDATE_CURRENT
				)
				builder.addAction(
						R.drawable.ic_stop_black_24dp,
						resources.getString(R.string.notification_stop_for_minutes, stopForMinutes),
						stopForMinutesAction
				)
			}
		}

		return builder.build()
	}

	private fun prepareNotificationBase(context: Context): NotificationCompat.Builder {
		val resources = context.resources
		val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
				?: throw NullPointerException("Launch intent for package is null.")

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

	private fun buildNotificationText(
			context: Context,
			location: Location,
			d: CollectionData
	): String {
		val resources = context.resources
		val sb = StringBuilder()
		val df = DecimalFormat.getNumberInstance()
		df.setRoundingMode(RoundingMode.HALF_UP)

		val lengthSystem = Preferences.getLengthSystem(context)
		//todo add localization support
		val delimiter = ", "

		if (location.hasSpeed()) {
			sb.append(resources.formatSpeed(context, location.speed.toDouble(), 1))
					.append(delimiter)
		}

		sb.append(
				resources.getString(
						R.string.notification_altitude,
						resources.formatDistance(location.altitude, 2, lengthSystem)
				)
		)
				.append(delimiter)

		val activity = d.activity
		if (activity != null) {
			sb.append(
					resources.getString(
							R.string.notification_activity,
							activity.getGroupedActivityName(context)
					)
			).append(delimiter)
		}

		val wifi = d.wifi
		if (wifi != null) {
			sb.append(resources.getString(R.string.notification_wifi, wifi.inRange.size))
					.append(delimiter)
		}

		val cell = d.cell
		if (cell != null && cell.registeredCells.isNotEmpty()) {
			val mainCell = cell.registeredCells.first()
			sb
					.append(
							resources.getString(
									R.string.notification_cell_current,
									mainCell.type.name,
									mainCell.dbm
							)
					)
					.append(' ')
					.append(
							resources.getQuantityString(
									R.plurals.notification_cell_count, cell.totalCount,
									cell.totalCount
							)
					)
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

