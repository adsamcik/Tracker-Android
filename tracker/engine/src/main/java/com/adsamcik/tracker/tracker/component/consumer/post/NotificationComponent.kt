package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.tracker.controller.forLiveSegment
import com.adsamcik.tracker.tracker.controller.hasLiveCapture
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

internal class NotificationComponent(
    private val resolveEntryPoint: (Context) -> NotificationComponentEntryPoint = { context ->
        EntryPointAccessors.fromApplication(context.applicationContext, NotificationComponentEntryPoint::class.java)
    },
) :
		PostTrackerComponent {

	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf()

	private var trackerNotificationManager: TrackerNotificationManager? = null

	private val requireTNotificationManager get() = requireNotNull(trackerNotificationManager)

	private val titleComponentList: MutableList<TrackerNotificationComponent> = mutableListOf()

	private val contentComponentList: MutableList<TrackerNotificationComponent> = mutableListOf()
	private val updateGate = NotificationUpdateGate(MAX_REFRESH_INTERVAL_NANOS)
	private val notificationLock = Any()
	private var lastPayload: NotificationPayload? = null
    private var activeGeneration = 0L
    private var savedUpdatesJob: Job? = null
    private var stateReader: TrackerStateReader? = null
    private var currentParams = TrackingParamsState(locationEnabled = false, activityEnabled = false,
        wifiEnabled = false, cellEnabled = false, stepsEnabled = false, barometerEnabled = false)
    private var lastData: Pair<Long, CollectionData>? = null

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
            activeGeneration++
            savedUpdatesJob?.cancel()
            savedUpdatesJob = null
            stateReader = null
            lastData = null
			trackerNotificationManager = null
			contentComponentList.clear()
			titleComponentList.clear()
			lastPayload = null
			updateGate.reset()
			TrackerNotificationManager.cancelTrackingNotification(context)
		}
	}

    override suspend fun onEnable(context: Context) = coroutineScope<Unit> {
        val generation = synchronized(notificationLock) {
            activeGeneration++
            savedUpdatesJob?.cancel()
            savedUpdatesJob = null
            trackerNotificationManager = null
            stateReader = null
            lastData = null
            titleComponentList.clear()
            contentComponentList.clear()
            lastPayload = null
            updateGate.reset()
            activeGeneration
        }
        val preferenceUpdate = async(DefaultDispatchersProvider.default) {
            TrackerNotificationProvider.updatePreferences(context)
            TrackerNotificationProvider.configuredComponents()
        }
        val entryPoint = resolveEntryPoint(context)
        val reader = entryPoint.trackerStateReader()
        val sessionInfo = requireNotNull(reader.sessionInfoFlow.value) {
            "TrackerService sessionInfo must be initialized before NotificationComponent.onEnable"
        }
        val params = entryPoint.trackingParamsRepository().data.first()
        val manager = TrackerNotificationManager(context, sessionInfo.isInitiatedByUser,
            notificationStyled = params.notificationStyled)
        val configured = preferenceUpdate.await()
        synchronized(notificationLock) {
            if (activeGeneration == generation) {
                trackerNotificationManager = manager
                stateReader = reader
                currentParams = params
                contentComponentList.addAll(configured.filter { it.preference.isInContent })
                titleComponentList.addAll(configured.filter { it.preference.isInTitle })
            }
        }
    }

    /** Reuses the controller's sole saved total, never a second Location reader or provider. */
    fun startSavedLocationUpdates(
        context: Context,
        reader: TrackerStateReader,
        params: TrackingParamsRepository,
        scope: CoroutineScope,
    ): Job = synchronized(notificationLock) {
        savedUpdatesJob?.cancel()
        val generation = activeGeneration
        scope.launch {
            combine(reader.savedLocationSummaryFlow, reader.sessionFlow, reader.sessionInfoFlow,
                reader.isServiceRunningFlow, params.data) { saved, session, info, running, choices ->
                LiveNotificationInput(saved, session, info != null && running, choices)
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(LiveNotificationInput(SavedLocationSummaryRead.Unavailable(HistoryProblemReason.STORAGE_UNAVAILABLE),
                    reader.sessionFlow.value, reader.hasLiveCapture, currentParams))
            }.collect { input ->
                synchronized(notificationLock) {
                    val session = input.session
                    if (activeGeneration != generation || trackerNotificationManager == null ||
                        !input.active || !reader.hasLiveCapture || session == null ||
                        reader.sessionFlow.value?.id != session.id) return@synchronized
                    currentParams = input.params
                    val data = lastData?.takeIf { it.first == session.id }?.second ?: MutableCollectionData(0L)
                    publish(context, session.asNotificationSession(), data,
                        input.saved.forLiveSegment(session.id, input.params.locationEnabled),
                        SystemClock.elapsedRealtimeNanos())
                }
            }
        }.also { savedUpdatesJob = it }
    }

    private data class LiveNotificationInput(val saved: SavedLocationSummaryRead,
        val session: TrackerSessionSnapshot?, val active: Boolean, val params: TrackingParamsState)

    private fun TrackerSessionSnapshot.asNotificationSession() = TrackerSession(
        id = id, start = start, end = end, isUserInitiated = isUserInitiated, collections = collections)

	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			cycle: TrackingCycle
	) {
        synchronized(notificationLock) {
            val reader = stateReader ?: return
            if (trackerNotificationManager == null || !reader.hasLiveCapture ||
                reader.sessionFlow.value?.id != session.id) return
            lastData = session.id to collectionData
            publish(context, session, collectionData,
                reader.savedLocationSummaryFlow.value.forLiveSegment(session.id, currentParams.locationEnabled),
                cycle.elapsedRealtimeNanos)
        }
    }

    private fun publish(context: Context, session: TrackerSession, data: CollectionData,
        savedLocation: SavedLocationSummaryRead, elapsedRealtimeNanos: Long) {
        val payload = NotificationPayload(generateTitle(context, data, session, savedLocation),
            buildNotificationText(context, session, data, savedLocation))
        lastPayload = payload
        if (updateGate.shouldUpdate(payload, elapsedRealtimeNanos)) notify(generateNotification(payload))
    }

	fun onForegroundServiceTypeChanged() {
		synchronized(notificationLock) {
			if (trackerNotificationManager == null || stateReader?.hasLiveCapture != true) return
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
			session: TrackerSession,
            savedLocation: SavedLocationSummaryRead
	): String {
		val sb = StringBuilder()
		titleComponentList.forEach {
			if (!it.isChosen(currentParams)) return@forEach
            val text = it.generateText(context, session, data, savedLocation) ?: return@forEach

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
			data: CollectionData,
            savedLocation: SavedLocationSummaryRead
	): String {
		val sb = StringBuilder()

		contentComponentList.forEach {
			if (!it.isChosen(currentParams)) return@forEach
            val text = it.generateText(context, session, data, savedLocation) ?: return@forEach

			sb.append(text).append(delimiter)
		}

		return sb.removeSuffix(delimiter).toString()
	}

	companion object {
		const val stopForMinutes = 30
		private const val MAX_REFRESH_INTERVAL_NANOS = 60 * Time.SECOND_IN_NANOSECONDS
	}
}
