package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import kotlinx.coroutines.flow.map

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ActivityDataProducerEntryPoint {
	fun activityRequestManager(): ActivityRequestManager
}

internal class ActivityDataProducer(
	changeReceiver: TrackerDataProducerObserver,
	trackingParamsRepository: TrackingParamsRepository? = null,
) : TrackerDataProducerComponent(
	changeReceiver,
	enabledFlow = trackingParamsRepository?.data?.map { it.activityEnabled },
) {
	override val preferenceKey: String
		get() = PreferenceKeys.ACTIVITY_ENABLED
	override val preferenceDefault: Boolean
		get() = PreferenceKeys.ACTIVITY_ENABLED_DEFAULT

	@Volatile
	private var lastSnapshot: ActivitySnapshot = ActivitySnapshot(ActivityInfo.UNKNOWN, -1L)

	private data class ActivitySnapshot(val activity: ActivityInfo, val elapsedTimeMillis: Long)

	private fun activityRequestManager(context: Context): ActivityRequestManager =
		EntryPointAccessors.fromApplication(
			context.applicationContext,
			ActivityDataProducerEntryPoint::class.java,
		).activityRequestManager()

	override fun onDataRequest(builder: TrackingCycleBuilder) {
		val snapshot = lastSnapshot
		val isActivityConfidentEnough =
				Time.elapsedRealtimeMillis - snapshot.elapsedTimeMillis <= MAX_ACTIVITY_AGE_IN_MILLIS

		if (isActivityConfidentEnough) {
			builder.activity = snapshot.activity
		} else {
			builder.activity = ActivityInfo.UNKNOWN
		}
	}

	@Suppress("UNUSED_PARAMETER")
	private fun onActivityChanged(context: Context, activity: ActivityInfo, elapsedTime: Long) {
		if (activity.confidence < ACTIVITY_CONFIDENCE_THRESHOLD) return

		if (activity.groupedActivity != GroupedActivity.UNKNOWN) {
			lastSnapshot = ActivitySnapshot(activity, elapsedTime)
		}
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		val minUpdateDelayInSeconds = BackgroundTrackingApi.cachedParams.minTimeSeconds
		activityRequestManager(context).requestActivity(
				context,
				ActivityRequestData(
						this::class,
						ActivityChangeRequestData(
								minUpdateDelayInSeconds,
								this::onActivityChanged
						)
				)
		)
	}

	override fun onDisable(context: Context) {
		super.onDisable(context)
		activityRequestManager(context).removeActivityRequest(context, this::class)
	}

	companion object {
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50
		private const val MAX_ACTIVITY_AGE_IN_MILLIS = 5 * Time.MINUTE_IN_MILLISECONDS
	}
}

