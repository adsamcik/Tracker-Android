package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder

internal class ActivityDataProducer(changeReceiver: TrackerDataProducerObserver) :
		TrackerDataProducerComponent(
				changeReceiver
		) {
	override val keyRes: Int
		get() = com.adsamcik.tracker.shared.preferences.R.string.settings_activity_enabled_key
	override val defaultRes: Int
		get() = com.adsamcik.tracker.shared.preferences.R.string.settings_activity_enabled_default

	@Volatile
	private var lastSnapshot: ActivitySnapshot = ActivitySnapshot(ActivityInfo.UNKNOWN, -1L)

	private data class ActivitySnapshot(val activity: ActivityInfo, val elapsedTimeMillis: Long)

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
		ActivityRequestManager.requestActivity(
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
		ActivityRequestManager.removeActivityRequest(context, this::class)
	}

	companion object {
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50
		private const val MAX_ACTIVITY_AGE_IN_MILLIS = 5 * Time.MINUTE_IN_MILLISECONDS
	}
}

