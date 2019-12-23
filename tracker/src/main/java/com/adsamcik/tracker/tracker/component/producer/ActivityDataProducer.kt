package com.adsamcik.tracker.tracker.component.producer

import android.content.Context
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.ActivityInfo
import com.adsamcik.tracker.common.data.GroupedActivity
import com.adsamcik.tracker.shared.preferences.Preferences

import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.TrackerDataProducerComponent
import com.adsamcik.tracker.tracker.component.TrackerDataProducerObserver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

internal class ActivityDataProducer(changeReceiver: TrackerDataProducerObserver) :
		TrackerDataProducerComponent(
				changeReceiver
		) {
	override val keyRes: Int
		get() = R.string.settings_activity_enabled_key
	override val defaultRes: Int
		get() = R.string.settings_activity_enabled_default

	private var lastActivity: ActivityInfo = ActivityInfo.UNKNOWN

	private var lastActivityElapsedTimeMillis = -1L

	override fun onDataRequest(tempData: MutableCollectionTempData) {
		val lastActivity = lastActivity
		val lastActivityElapsedTimeMillis = lastActivityElapsedTimeMillis
		val isActivityConfidentEnough =
				Time.elapsedRealtimeMillis - lastActivityElapsedTimeMillis <= MAX_ACTIVITY_AGE_IN_MILLIS

		if (isActivityConfidentEnough) {
			tempData.setActivity(lastActivity)
		} else {
			tempData.setActivity(ActivityInfo.UNKNOWN)
		}
	}

	private fun onActivityChanged(context: Context, activity: ActivityInfo, elapsedTime: Long) {
		if (activity.confidence < ACTIVITY_CONFIDENCE_THRESHOLD) return

		if (activity.groupedActivity != GroupedActivity.UNKNOWN) {
			lastActivity = activity
			lastActivityElapsedTimeMillis = elapsedTime
		}
	}

	override fun onEnable(context: Context) {
		super.onEnable(context)
		val preferences = Preferences.getPref(context)
		val minUpdateDelayInSeconds = preferences.getIntRes(
				R.string.settings_tracking_min_time_key,
				R.integer.settings_tracking_min_time_default
		)
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

