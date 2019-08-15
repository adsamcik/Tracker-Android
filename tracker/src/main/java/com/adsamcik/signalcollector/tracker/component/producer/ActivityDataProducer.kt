package com.adsamcik.signalcollector.tracker.component.producer

import android.content.Context
import com.adsamcik.signalcollector.activity.ActivityRequestData
import com.adsamcik.signalcollector.activity.api.ActivityRequestManager
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.component.TrackerDataProducerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerDataProducerObserver
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionTempData

internal class ActivityDataProducer(changeReceiver: TrackerDataProducerObserver) : TrackerDataProducerComponent(
		changeReceiver) {
	override val keyRes: Int
		get() = R.string.settings_activity_enabled_key
	override val defaultRes: Int
		get() = R.string.settings_activity_enabled_default

	private var lastActivity: ActivityInfo = ActivityInfo.UNKNOWN

	private var lastActivityElapsedTimeMillis = -1L

	override fun onDataRequest(tempData: MutableCollectionTempData) {
		val lastActivity = lastActivity
		val lastActivityElapsedTimeMillis = lastActivityElapsedTimeMillis
		val isActivityConfidentEnough = (lastActivity.confidence >= ACTIVITY_CONFIDENCE_THRESHOLD)
				.and(Time.elapsedRealtimeMillis - lastActivityElapsedTimeMillis <= MAX_ACTIVITY_AGE_IN_MILLIS)

		if (isActivityConfidentEnough) {
			tempData.setActivity(lastActivity)
		} else {
			tempData.setActivity(ActivityInfo.UNKNOWN)
		}
	}

	private fun onActivityChanged(context: Context, activity: ActivityInfo, elapsedTime: Long) {
		lastActivity = activity
		lastActivityElapsedTimeMillis = elapsedTime
	}

	override fun onEnable(context: Context) {
		val preferences = Preferences.getPref(context)
		val minUpdateDelayInSeconds = preferences.getIntRes(R.string.settings_tracking_min_time_key,
				R.integer.settings_tracking_min_time_default)
		ActivityRequestManager.requestActivity(context,
				ActivityRequestData(this::class, minUpdateDelayInSeconds, listOf(), this::onActivityChanged))
	}

	override fun onDisable(context: Context) {
		ActivityRequestManager.removeActivityRequest(context, this::class)
	}

	companion object {
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50
		private const val MAX_ACTIVITY_AGE_IN_MILLIS = Time.MINUTE_IN_MILLISECONDS
	}
}
