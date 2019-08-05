package com.adsamcik.signalcollector.tracker.component.producer

import android.content.Context
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.component.TrackerDataProducerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerDataProducerObserver
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionTempData

internal class ActivityDataProducer(changeReceiver: TrackerDataProducerObserver) : TrackerDataProducerComponent(changeReceiver) {
	override val keyRes: Int
		get() = R.string.settings_activity_enabled_key
	override val defaultRes: Int
		get() = R.string.settings_activity_enabled_default

	override fun onDataRequest(tempData: MutableCollectionTempData) {
		val lastActivity = ActivityService.lastActivity
		val lastActivityElapsedTimeMillis = ActivityService.lastActivityElapsedTimeMillis
		val isActivityConfidentEnough = (lastActivity.confidence >= ACTIVITY_CONFIDENCE_THRESHOLD)
				.and(Time.elapsedRealtimeMillis - lastActivityElapsedTimeMillis <= MAX_ACTIVITY_AGE_IN_MILLIS)

		if (isActivityConfidentEnough) {
			tempData.setActivity(ActivityService.lastActivity)
		} else {
			tempData.setActivity(ActivityInfo.UNKNOWN)
		}
	}

	override fun onEnable(context: Context) {
		val preferences = Preferences.getPref(context)
		val minUpdateDelayInSeconds = preferences.getIntRes(R.string.settings_tracking_min_time_key, R.integer.settings_tracking_min_time_default)
		ActivityService.requestActivity(context, this::class, minUpdateDelayInSeconds)
	}

	override fun onDisable(context: Context) {
		ActivityService.removeActivityRequest(context, this::class)
	}

	companion object {
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50
		private const val MAX_ACTIVITY_AGE_IN_MILLIS = Time.MINUTE_IN_MILLISECONDS
	}
}