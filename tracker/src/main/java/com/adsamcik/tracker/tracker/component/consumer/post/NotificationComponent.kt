package com.adsamcik.tracker.tracker.component.consumer.post

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.TrackerNotificationManager
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import com.adsamcik.tracker.tracker.notification.TrackerNotificationProvider
import com.adsamcik.tracker.tracker.receiver.TrackerNotificationReceiver
import com.adsamcik.tracker.tracker.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class NotificationComponent : PostTrackerComponent {

	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private var trackerNotificationManager: TrackerNotificationManager? = null

	private val requireTNotificationManager get() = requireNotNull(trackerNotificationManager)

	private val titleComponentList: MutableList<TrackerNotificationComponent> = mutableListOf()

	private val contentComponentList: MutableList<TrackerNotificationComponent> = mutableListOf()

	//todo add localization support
	private val delimiter = ", "

	override suspend fun onDisable(context: Context) {
		trackerNotificationManager = null
		contentComponentList.clear()
		titleComponentList.clear()
	}

	override suspend fun onEnable(context: Context) = coroutineScope<Unit> {
		val preferenceUpdate = async(Dispatchers.Default) {
			TrackerNotificationProvider.updatePreferences(context)
			contentComponentList.addAll(TrackerNotificationProvider.internalActiveList
					                            .filter { it.preference.isInContent }
					                            .sortedBy { it.preference.order })

			titleComponentList.addAll(TrackerNotificationProvider.internalActiveList
					                          .filter { it.preference.isInTitle }
					                          .sortedBy { it.preference.order })
		}
		trackerNotificationManager = TrackerNotificationManager(context)

		preferenceUpdate.await()
	}

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		notify(generateNotification(context, collectionData, session))
	}

	fun onError(context: Context, @StringRes textRes: Int) {
		val builder = requireTNotificationManager.createBuilder()
		builder.setContentTitle(context.getString(textRes))
		notify(builder)
	}

	private fun notify(builder: NotificationCompat.Builder) =
			requireTNotificationManager.notify(builder)


	private fun buildContent(
			context: Context,
			builder: NotificationCompat.Builder,
			session: TrackerSession,
			data: CollectionData
	) {
		builder.setContentTitle(generateTitle(context, data, session))

		val notificationText = buildNotificationText(context, session, data)

		builder.setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
		builder.setContentText(notificationText)
	}

	private fun generateTitle(
			context: Context,
			data: CollectionData,
			session: TrackerSession
	): String {
		val sb = StringBuilder()
		titleComponentList.forEach {
			val text = it.generateText(context, session, data) ?: return@forEach

			sb.append(text).append(delimiter)
		}

		return if (sb.isEmpty()) {
			context.getString(R.string.notification_tracking_active)
		} else {
			sb.removeSuffix(delimiter).toString()
		}
	}

	private fun generateNotification(
			context: Context,
			data: CollectionData,
			session: TrackerSession
	): NotificationCompat.Builder {
		val builder = requireTNotificationManager.createBuilder()

		val resources = context.resources

		buildContent(context, builder, session, data)

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
			session: TrackerSession,
			data: CollectionData
	): String {
		context.resources
		val sb = StringBuilder()

		contentComponentList.forEach {
			val text = it.generateText(context, session, data) ?: return@forEach

			sb.append(text).append(delimiter)
		}

		return sb.removeSuffix(delimiter).toString()
	}

	companion object {
		const val stopForMinutes = 30
	}
}

