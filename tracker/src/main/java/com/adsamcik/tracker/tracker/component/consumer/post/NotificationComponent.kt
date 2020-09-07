package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.extension.requireValue
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationProvider
import com.adsamcik.tracker.tracker.service.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal class NotificationComponent :
		PostTrackerComponent {

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
		trackerNotificationManager = TrackerNotificationManager(
				context,
				TrackerService.sessionInfo.requireValue.isInitiatedByUser
		)

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
		buildContent(context, builder, session, data)
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

