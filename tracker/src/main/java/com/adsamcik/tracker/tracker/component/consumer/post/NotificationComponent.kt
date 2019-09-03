package com.adsamcik.tracker.tracker.component.consumer.post

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.location.Location
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.common.extension.formatDistance
import com.adsamcik.tracker.common.extension.formatSpeed
import com.adsamcik.tracker.common.preference.Preferences
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.TrackerNotificationManager
import com.adsamcik.tracker.tracker.TrackerNotificationManager.Companion.NOTIFICATION_ID
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver
import com.adsamcik.tracker.tracker.service.TrackerService
import java.math.RoundingMode
import java.text.DecimalFormat

internal class NotificationComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private var trackerNotificationManager: TrackerNotificationManager? = null

	private val requireTNotificationManager get() = requireNotNull(trackerNotificationManager)

	override suspend fun onDisable(context: Context) {
		trackerNotificationManager = null
	}

	override suspend fun onEnable(context: Context) {
		trackerNotificationManager = TrackerNotificationManager(context)
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

	fun onError(context: Context, @StringRes textRes: Int) {
		val builder = requireTNotificationManager.createBuilder()
		builder.setContentTitle(context.getString(textRes))
		notify(builder)
	}

	private fun notify(builder: NotificationCompat.Builder) =
			requireTNotificationManager.notify(builder)


	private fun generateNoGpsTitle(resources: Resources, builder: NotificationCompat.Builder) {
		builder.setContentTitle(resources.getString(R.string.notification_looking_for_gps))
	}

	private fun buildContent(
			context: Context,
			resources: Resources,
			builder: NotificationCompat.Builder,
			location: Location? = null,
			data: CollectionData? = null
	) {
		when {
			location == null || data == null -> generateNoGpsTitle(resources, builder)
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
	}

	private fun generateNotification(
			context: Context,
			location: Location? = null,
			data: CollectionData? = null
	): NotificationCompat.Builder {
		val builder = requireTNotificationManager.createBuilder()

		val resources = context.resources

		buildContent(context, resources, builder, location, data)

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

		return builder
	}

	private fun buildNotificationText(
			context: Context,
			location: Location,
			d: CollectionData
	): String {
		val resources = context.resources
		val sb = StringBuilder()
		val df = DecimalFormat.getNumberInstance()
		df.roundingMode = RoundingMode.HALF_UP

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
		const val stopForMinutes = 30
	}
}

