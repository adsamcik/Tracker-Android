package com.adsamcik.signalcollector.tracker.api

import android.content.Context
import androidx.annotation.MainThread
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.activity.ActivityRequestCallback
import com.adsamcik.signalcollector.activity.ActivityRequestData
import com.adsamcik.signalcollector.activity.ActivityTransitionData
import com.adsamcik.signalcollector.activity.ActivityTransitionType
import com.adsamcik.signalcollector.activity.api.ActivityRequestManager
import com.adsamcik.signalcollector.common.Assist
import com.adsamcik.signalcollector.common.data.DetectedActivity
import com.adsamcik.signalcollector.common.data.GroupedActivity
import com.adsamcik.signalcollector.common.extension.powerManager
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
import com.adsamcik.signalcollector.tracker.service.ActivityWatcherService
import com.adsamcik.signalcollector.tracker.service.TrackerService

object BackgroundTrackingApi {
	//todo add option for this in settings
	private const val REQUIRED_CONFIDENCE = 75
	private var appContext: Context? = null

	private val callback: ActivityRequestCallback = { context, activity, _ ->
		if (activity.confidence >= REQUIRED_CONFIDENCE) {
			if (TrackerServiceApi.isActive) {
				if (!requireNotNull(TrackerServiceApi.sessionInfo).isInitiatedByUser &&
						!canContinueBackgroundTracking(context, activity.groupedActivity)) {
					TrackerServiceApi.stopService(context)
				}
			} else {
				if (canBackgroundTrack(context, activity.groupedActivity) &&
						canTrackerServiceBeStarted(context)) {
					TrackerServiceApi.startService(context, isUserInitiated = false)
				}
			}
		}
	}

	private val observer: Observer<Int> = Observer {
		val context = requireNotNull(appContext)
		if (it == GroupedActivity.STILL.ordinal) {
			disable(context)
		} else {
			enable(context)
		}
	}

	private var isActive = false

	private fun canTrackerServiceBeStarted(context: Context) = !TrackerLocker.isLocked.value &&
			!context.powerManager.isPowerSaveMode &&
			Assist.canTrack(context)

	/**
	 * Checks if background tracking can be activated
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can be activated
	 */
	private fun canBackgroundTrack(context: Context, groupedActivity: GroupedActivity): Boolean {
		val preferences = Preferences.getPref(context)
		if (groupedActivity.isIdle ||
				TrackerService.isServiceRunning.value ||
				preferences.getBooleanRes(R.string.settings_disabled_recharge_key, R.string.settings_disabled_recharge_default)) {
			return false
		}

		val preference = Preferences.getPref(context)
				.getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default)
		val prefActivity = GroupedActivity.values()[preference]
		return prefActivity != GroupedActivity.STILL && (prefActivity == groupedActivity || prefActivity.ordinal > groupedActivity.ordinal)
	}

	/**
	 * Checks if background tracking should stop.
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can continue running
	 */
	private fun canContinueBackgroundTracking(context: Context, groupedActivity: GroupedActivity): Boolean {
		if (groupedActivity == GroupedActivity.STILL) return false

		val preference = getBackgroundTrackingActivityRequirement(context)
		val prefActivity = GroupedActivity.values()[preference]
		return prefActivity == GroupedActivity.IN_VEHICLE ||
				(prefActivity == GroupedActivity.ON_FOOT &&
						(groupedActivity == GroupedActivity.ON_FOOT || groupedActivity == GroupedActivity.UNKNOWN))
	}

	private fun getBackgroundTrackingActivityRequirement(context: Context) =
			Preferences.getPref(context).getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default)

	private fun buildTransitions(context: Context): List<ActivityTransitionData> {
		val transitions = mutableListOf<ActivityTransitionData>()
		val requiredActivityId = getBackgroundTrackingActivityRequirement(context)

		if (requiredActivityId >= GroupedActivity.IN_VEHICLE.ordinal) {
			transitions.add(ActivityTransitionData(DetectedActivity.IN_VEHICLE, ActivityTransitionType.ENTER))
			transitions.add(ActivityTransitionData(DetectedActivity.ON_BICYCLE, ActivityTransitionType.ENTER))
		}

		if (requiredActivityId >= GroupedActivity.ON_FOOT.ordinal) {
			transitions.add(ActivityTransitionData(DetectedActivity.ON_FOOT, ActivityTransitionType.ENTER))
			transitions.add(ActivityTransitionData(DetectedActivity.RUNNING, ActivityTransitionType.ENTER))
			transitions.add(ActivityTransitionData(DetectedActivity.WALKING, ActivityTransitionType.ENTER))
		}

		return transitions
	}

	private fun enable(context: Context) {
		if (isActive) return

		val interval = Preferences.getPref(context)
				.getIntResString(R.string.settings_activity_freq_key, R.string.settings_activity_freq_default)
		val transitions = buildTransitions(context)
		val requestData = ActivityRequestData(this::class, interval, transitions, callback)
		ActivityRequestManager.requestActivity(context, requestData)
		ActivityWatcherService.poke(context)

		isActive = true
	}

	private fun disable(context: Context) {
		if (!isActive) return

		ActivityRequestManager.removeActivityRequest(context, this::class)
		ActivityWatcherService.poke(context)

		isActive = false
	}

	@MainThread
	fun initialize(context: Context) {
		if (appContext != null) return

		appContext = context.applicationContext

		PreferenceObserver.observe(context, R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default, observer)
	}
}
