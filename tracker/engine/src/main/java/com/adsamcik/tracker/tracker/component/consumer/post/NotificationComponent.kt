package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.notification.TrackerNotificationComponent
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Hilt EntryPoint for accessing read-only tracker state from NotificationComponent
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationComponentEntryPoint {
	fun trackerStateReader(): TrackerStateReader
	fun trackingParamsRepository(): TrackingParamsRepository
}

internal class NotificationComponent :
		PostTrackerComponent {

	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private var trackerNotificationManager: TrackerNotificationManager? = null

	private val requireTNotificationManager get() = requireNotNull(trackerNotificationManager)

	private val titleComponentList: MutableList<TrackerNotificationComponent> = mutableListOf()

	private val contentComponentList: MutableList<TrackerNotificationComponent> = mutableListOf()
	private val updateGate = NotificationUpdateGate(MAX_REFRESH_INTERVAL_NANOS)
	private val notificationLock = Any()
	private var lastPayload: NotificationPayload? = null

	// Separator for notification text components
	// Uses comma-space which is appropriate for most locales
	private val delimiter = ", "

	override suspend fun onDisable(context: Context) {
		onServiceStopped(context)
	}

	/**
	 * Prevents a final in-flight tracking cycle from recreating the foreground notification after
	 * the service has removed it. This is synchronous because service teardown continues on a
	 * separate scope after [android.app.Service.onDestroy] returns.
	 */
	fun onServiceStopped(context: Context) {
		synchronized(notificationLock) {
			trackerNotificationManager = null
			contentComponentList.clear()
			titleComponentList.clear()
			lastPayload = null
			updateGate.reset()
			TrackerNotificationManager.cancelTrackingNotification(context)
		}
	}

	override suspend fun onEnable(context: Context) = coroutineScope<Unit> {
		val preferenceUpdate = async(DefaultDispatchersProvider.default) {
			TrackerNotificationProvider.updatePreferences(context)
			val configuredComponents = TrackerNotificationProvider.configuredComponents()
			contentComponentList.addAll(configuredComponents.filter { it.preference.isInContent })

			titleComponentList.addAll(configuredComponents.filter { it.preference.isInTitle })
		}
		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			NotificationComponentEntryPoint::class.java
		)
		val sessionInfoFlow = entryPoint.trackerStateReader().sessionInfoFlow
		val sessionInfo = requireNotNull(sessionInfoFlow.value) {
			"TrackerService sessionInfo must be initialized before NotificationComponent.onEnable"
		}
		val params = entryPoint.trackingParamsRepository().data.first()
		trackerNotificationManager = TrackerNotificationManager(
				context,
				sessionInfo.isInitiatedByUser,
				notificationStyled = params.notificationStyled
		)

		preferenceUpdate.await()
		synchronized(notificationLock) { updateGate.reset() }
	}

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			cycle: TrackingCycle
	) {
		synchronized(notificationLock) {
			if (trackerNotificationManager == null) return
			val payload = NotificationPayload(
				title = generateTitle(context, collectionData, session),
				text = buildNotificationText(context, session, collectionData),
			)
			lastPayload = payload
			if (updateGate.shouldUpdate(payload, cycle.elapsedRealtimeNanos)) {
				notify(generateNotification(payload))
			}
		}
	}

	fun onForegroundServiceTypeChanged() {
		synchronized(notificationLock) {
			if (trackerNotificationManager == null) return
			updateGate.reset()
			lastPayload?.let { notify(generateNotification(it)) }
		}
	}

	fun onError(context: Context, @StringRes textRes: Int) {
		synchronized(notificationLock) {
			val manager = trackerNotificationManager ?: return
			updateGate.reset()
			val builder = manager.createBuilder()
			builder.setContentTitle(context.getString(textRes))
			notify(builder)
		}
	}

	private fun notify(builder: NotificationCompat.Builder) =
			requireTNotificationManager.notify(builder)


	private fun buildContent(
			builder: NotificationCompat.Builder,
			payload: NotificationPayload,
	) {
		builder.setContentTitle(payload.title)
		builder.setStyle(NotificationCompat.BigTextStyle().bigText(payload.text))
		builder.setContentText(payload.text)
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
			payload: NotificationPayload,
	): NotificationCompat.Builder {
		val builder = requireTNotificationManager.createBuilder()
		buildContent(builder, payload)
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
		private const val MAX_REFRESH_INTERVAL_NANOS = 60 * Time.SECOND_IN_NANOSECONDS
	}
}
