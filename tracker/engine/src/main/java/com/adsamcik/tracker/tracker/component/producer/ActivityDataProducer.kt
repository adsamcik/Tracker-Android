package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.ActivityUpdateSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import com.adsamcik.tracker.tracker.data.toLegacyActivityInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicLong

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ActivityDataProducerEntryPoint {
	fun activityRequestManager(): ActivityRequestManager
}

internal class ActivityDataProducer(
	changeReceiver: TrackerDataProducerObserver,
	trackingParamsRepository: TrackingParamsRepository? = null,
	dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val activityRequestManagerProvider: (Context) -> ActivityRequestManager = { context ->
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			ActivityDataProducerEntryPoint::class.java,
		).activityRequestManager()
	},
) : TrackerDataProducerComponent(
	changeReceiver,
	dispatchers = dispatchers,
	enabledFlow = trackingParamsRepository?.data?.map { it.activityEnabled },
) {
	override val preferenceKey: String
		get() = PreferenceKeys.ACTIVITY_ENABLED
	override val preferenceDefault: Boolean
		get() = PreferenceKeys.ACTIVITY_ENABLED_DEFAULT

	@Volatile
	private var lastSnapshot: ActivitySnapshot = ActivitySnapshot(
		activity = ActivityInfo.UNKNOWN,
		elapsedTimeMillis = -1L,
		generation = 0L,
	)
	private val generation = AtomicLong()
	private var lastEmittedGeneration = 0L
	private val activityScope = CoroutineScope(
		SupervisorJob() +
			((dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main)
	)
	private var activityUpdatesJob: Job? = null

	private data class ActivitySnapshot(
		val activity: ActivityInfo,
		val elapsedTimeMillis: Long,
		val generation: Long,
	)

	private fun activityRequestManager(context: Context): ActivityRequestManager =
		activityRequestManagerProvider(context)

	override fun onDataRequest(builder: TrackingCycleBuilder) {
		val snapshot = lastSnapshot
		val isActivityConfidentEnough =
				Time.elapsedRealtimeMillis - snapshot.elapsedTimeMillis <= MAX_ACTIVITY_AGE_IN_MILLIS

		if (isActivityConfidentEnough) {
			builder.activity = snapshot.activity
			if (snapshot.generation > lastEmittedGeneration) {
				builder.activityFresh = true
				lastEmittedGeneration = snapshot.generation
			}
		} else {
			builder.activity = ActivityInfo.UNKNOWN
		}
	}

	internal fun recordActivity(activity: ActivityInfo, elapsedTime: Long) {
		if (activity.confidence < ACTIVITY_CONFIDENCE_THRESHOLD) return

		if (activity.groupedActivity != GroupedActivity.UNKNOWN) {
			lastSnapshot = ActivitySnapshot(
				activity = activity,
				elapsedTimeMillis = elapsedTime,
				generation = generation.incrementAndGet(),
			)
		}
	}

	override suspend fun onEnable(context: Context) {
		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds
		val requestManager = activityRequestManager(context)
		activityUpdatesJob?.cancel()
		activityUpdatesJob = requestManager.activityUpdates
			.onEach(::recordActivity)
			.launchIn(activityScope)
		try {
			val requestStarted = requestManager.requestActivity(
					context,
					ActivityRequestData(
							this::class,
							ActivityChangeRequestData(minUpdateDelayInSeconds)
					)
			)
			if (!requestStarted) {
				error("Unable to start activity recognition request")
			}
			super.onEnable(context)
		} catch (exception: CancellationException) {
			activityUpdatesJob?.cancelAndJoin()
			activityUpdatesJob = null
			throw exception
		} catch (exception: Exception) {
			activityUpdatesJob?.cancelAndJoin()
			activityUpdatesJob = null
			throw exception
		}
	}

	override suspend fun onDisable(context: Context) {
		val updatesJob = activityUpdatesJob
		activityUpdatesJob = null
		updatesJob?.cancelAndJoin()
		clearSnapshot()
		activityRequestManager(context).removeActivityRequest(context, this::class)
		super.onDisable(context)
	}

	private fun clearSnapshot() {
		val currentGeneration = generation.get()
		lastSnapshot = ActivitySnapshot(
			activity = ActivityInfo.UNKNOWN,
			elapsedTimeMillis = -1L,
			generation = currentGeneration,
		)
		lastEmittedGeneration = currentGeneration
	}

	internal fun recordActivity(update: ActivityUpdate) {
		if (update.source != ActivityUpdateSource.RECOGNITION) return
		recordActivity(update.activity.toLegacyActivityInfo(), update.elapsedTimeMillis)
	}

	companion object {
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50
		private const val MAX_ACTIVITY_AGE_IN_MILLIS = 5 * Time.MINUTE_IN_MILLISECONDS
	}
}
