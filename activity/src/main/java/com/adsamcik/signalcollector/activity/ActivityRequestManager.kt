package com.adsamcik.signalcollector.activity

import android.content.Context
import android.util.SparseArray
import com.adsamcik.signalcollector.activity.service.ActivityService
import com.adsamcik.signalcollector.common.Reporter
import com.adsamcik.signalcollector.common.data.GroupedActivity
import com.adsamcik.signalcollector.common.preference.Preferences
import kotlin.reflect.KClass

object ActivityRequestManager {
	private var activeRequestArray = SparseArray<ActivityRequestInfo>()
	private var minUpdateRate = Integer.MAX_VALUE
	private var isBackgroundTracking: Boolean = false


	/**
	 * Request activity updates
	 *
	 * @param context    context
	 * @param tClass     class that requests update
	 * @param updateRate update rate in seconds
	 * @return true if success
	 */
	fun requestActivity(context: Context,
	                    tClass: KClass<*>,
	                    updateRate: Int = Preferences.getPref(context).getIntResString(R.string.settings_activity_freq_key, R.string.settings_activity_freq_default)): Boolean =
			requestActivityInternal(context, tClass, updateRate, false)

	/**
	 * Request auto tracking updates
	 * Checks if autotracking is allowed
	 *
	 * @param context context
	 * @param tClass  class that requests update
	 * @return true if success
	 */
	fun requestAutoTracking(context: Context,
	                        tClass: KClass<*>,
	                        updateRate: Int = Preferences.getPref(context).getIntResString(R.string.settings_activity_freq_key, R.string.settings_activity_freq_default)): Boolean {
		val preferences = Preferences.getPref(context)

		if (preferences.getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default) > 0 &&
				requestActivityInternal(context, tClass, updateRate, true)) {
			isBackgroundTracking = true
			return true
		}
		return false
	}

	/**
	 * Internal activity request
	 */
	private fun requestActivityInternal(context: Context, tClass: KClass<*>, updateRate: Int, backgroundTracking: Boolean): Boolean {
		setMinUpdateRate(context, updateRate)
		val hash = tClass.hashCode()
		val index = activeRequestArray.indexOfKey(hash)
		if (index < 0) {
			activeRequestArray.append(hash, ActivityRequestInfo(updateRate, backgroundTracking))
		} else {
			val ari = ActivityService.mActiveRequests.valueAt(index)
			ari.isBackgroundTracking = backgroundTracking
			ari.updateDelay = updateRate
		}

		return true
	}

	/**
	 * Removes previous activity request
	 */
	fun removeActivityRequest(context: Context, tClass: KClass<*>) {
		val index = ActivityService.mActiveRequests.indexOfKey(tClass.hashCode())
		if (index >= 0) {
			val request = ActivityService.mActiveRequests.valueAt(index)

			ActivityService.mActiveRequests.removeAt(index)
			if (request.isBackgroundTracking ||
					(ActivityService.mMinUpdateRate == request.updateDelay && ActivityService.mActiveRequests.size() > 0)) {
				val ari = ActivityService.generateExtremeRequest()
				ActivityService.mBackgroundTracking = ari.isBackgroundTracking
				ActivityService.setMinUpdateRate(context, ari.updateDelay)
			}
		} else {
			Reporter.report(Throwable("Trying to remove class that is not subscribed (" + tClass.java.name + ")"))
		}

		if (ActivityService.mActiveRequests.size() == 0) {
			ActivityRecognition.getClient(context).removeActivityUpdates(ActivityService.getActivityDetectionPendingIntent(context))
			ActivityService.mActiveRequests = SparseArray()
		}
	}

	private fun setMinUpdateRate(context: Context, minUpdateRate: Int) {
		if (minUpdateRate < ActivityService.mMinUpdateRate) {
			ActivityService.mMinUpdateRate = minUpdateRate
			ActivityService.initializeActivityClient(context, minUpdateRate)
		}
	}

	/**
	 * Merges all request into a single request that returns has values to satisfy all requests
	 * Eg. if 2 requests have different update delays, extreme request will have the value of the smaller delay
	 */
	private fun generateExtremeRequest(): ActivityRequestInfo {
		if (ActivityService.mActiveRequests.size() == 0) {
			return ActivityRequestInfo(Integer.MIN_VALUE, false)
		}

		var backgroundTracking = false
		var min = Integer.MAX_VALUE
		for (i in 0 until ActivityService.mActiveRequests.size()) {
			val ari = ActivityService.mActiveRequests.valueAt(i)
			if (ari.updateDelay < min) min = ari.updateDelay
			backgroundTracking = backgroundTracking or ari.isBackgroundTracking
		}
		return ActivityRequestInfo(min, backgroundTracking)
	}

	/**
	 * Checks if background tracking can be activated
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can be activated
	 */
	private fun canBackgroundTrack(context: Context, groupedActivity: GroupedActivity): Boolean {
		with(Preferences.getPref(context)) {
			if (isActivityIdle(groupedActivity) ||
					TrackerService.isServiceRunning.value ||
					getBooleanRes(R.string.settings_disabled_recharge_key, R.string.settings_disabled_recharge_default)) {
				return false
			}

			val preference = getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default)
			val prefActivity = GroupedActivity.values()[preference]
			return prefActivity != GroupedActivity.STILL && (prefActivity == groupedActivity || prefActivity.ordinal > groupedActivity.ordinal)
		}
	}

	/**
	 * Checks if background tracking should stop.
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can continue running
	 */
	private fun canContinueBackgroundTracking(context: Context, groupedActivity: GroupedActivity): Boolean {
		if (groupedActivity == GroupedActivity.STILL) return false

		val preference = Preferences.getPref(context).getIntResString(R.string.settings_tracking_activity_key, R.string.settings_tracking_activity_default)
		val prefActivity = GroupedActivity.values()[preference]
		return prefActivity == GroupedActivity.IN_VEHICLE ||
				(prefActivity == GroupedActivity.ON_FOOT &&
						(groupedActivity == GroupedActivity.ON_FOOT || groupedActivity == GroupedActivity.UNKNOWN))
	}
}