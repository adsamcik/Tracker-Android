package com.adsamcik.signalcollector.tracker.api

import android.content.Context
import androidx.lifecycle.Observer
import com.adsamcik.signalcollector.activity.ActivityRequest
import com.adsamcik.signalcollector.activity.ActivityRequestCallback
import com.adsamcik.signalcollector.activity.ActivityRequestManager
import com.adsamcik.signalcollector.common.data.GroupedActivity
import com.adsamcik.signalcollector.common.preference.Preferences
import com.adsamcik.signalcollector.common.preference.observer.PreferenceObserver
import com.adsamcik.signalcollector.tracker.R
import com.adsamcik.signalcollector.tracker.service.TrackerService

object BackgroundTrackingApi {
	private var appContext: Context? = null

	private val callback: ActivityRequestCallback = { context, activity, _ ->
		if (TrackerServiceApi.isActive) {
			if (!canContinueBackgroundTracking(context, activity.groupedActivity)) {
				TrackerServiceApi.stopService(context)
			}
		} else {
			if (canBackgroundTrack(context, activity.groupedActivity)) {
				TrackerServiceApi.startService(context, isUserInitiated = false)
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

		val preference = Preferences.getPref(context).getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default)
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

	private fun enable(context: Context) {
		if (isActive) return

		val interval = Preferences.getPref(context).getIntResString(R.string.settings_activity_freq_key, R.string.settings_activity_freq_default)
		val requestData = ActivityRequest(this::class, interval, callback)
		ActivityRequestManager.requestActivity(context, requestData)

		isActive = true
	}

	private fun disable(context: Context) {
		if (!isActive) return

		ActivityRequestManager.removeActivityRequest(context, this::class)
		isActive = false
	}

	fun initialize(context: Context) {
		appContext = context.applicationContext

		PreferenceObserver.observe(context, R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default, observer)
	}
}