package com.adsamcik.tracker.tracker.api

import android.content.Context
import androidx.annotation.MainThread
import androidx.lifecycle.Observer
import com.adsamcik.tracker.activity.ActivityChangeRequestCallback
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionRequestCallback
import com.adsamcik.tracker.activity.ActivityTransitionRequestData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.extension.powerManager
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferencesAssist
import com.adsamcik.tracker.shared.preferences.observer.PreferenceObserver
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.tracker.service.TrackerService

/**
 * Exposed methods for background tracking
 */
@Suppress("TooManyFunctions")
object BackgroundTrackingApi {
	//todo add option for this in settings
	private const val REQUIRED_CONFIDENCE = 75
	private var appContext: Context? = null

	//todo add this as option
	private val callback: ActivityChangeRequestCallback = { context, activity, _ ->
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

	private val transitionCallback: ActivityTransitionRequestCallback = { context, activity, _ ->
		if (TrackerServiceApi.isActive) {
			if (!requireNotNull(TrackerServiceApi.sessionInfo).isInitiatedByUser &&
					!canContinueBackgroundTracking(context, activity.activity.groupedActivity)) {
				TrackerServiceApi.stopService(context)
			}
		} else {
			if (canBackgroundTrack(context, activity.activity.groupedActivity) &&
					canTrackerServiceBeStarted(context)) {
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

	private val transitionObserver: Observer<Boolean> = Observer {
		val context = requireNotNull(appContext)
		reinitializeRequest(context, it)
	}

	private var isActive = false

	private fun canTrackerServiceBeStarted(context: Context) = !TrackerLocker.isLocked.value &&
			!context.powerManager.isPowerSaveMode &&
			PreferencesAssist.hasAnythingToTrack(context)

	/**
	 * Checks if background tracking can be activated
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can be activated
	 */
	private fun canBackgroundTrack(context: Context, groupedActivity: GroupedActivity): Boolean {
		val preferences = Preferences.getPref(context)
		if (groupedActivity.isStillOrUnknown ||
				TrackerService.isServiceRunning.value ||
				preferences.getBooleanRes(
						R.string.settings_disabled_recharge_key,
						R.string.settings_disabled_recharge_default
				)) {
			return false
		}

		val preference = Preferences.getPref(context)
				.getIntResString(
						R.string.settings_tracking_activity_key,
						R.string.settings_tracking_activity_default
				)
		val prefActivity = GroupedActivity.values()[preference]
		return prefActivity != GroupedActivity.STILL &&
				(prefActivity == groupedActivity || prefActivity.ordinal > groupedActivity.ordinal)
	}

	/**
	 * Checks if background tracking should stop.
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can continue running
	 */
	private fun canContinueBackgroundTracking(
			context: Context,
			groupedActivity: GroupedActivity
	): Boolean {
		if (groupedActivity == GroupedActivity.STILL) return false

		val preference = getBackgroundTrackingActivityRequirement(context)
		val prefActivity = GroupedActivity.values()[preference]
		return prefActivity == GroupedActivity.IN_VEHICLE ||
				(prefActivity == GroupedActivity.ON_FOOT &&
						(groupedActivity == GroupedActivity.ON_FOOT || groupedActivity == GroupedActivity.UNKNOWN))
	}

	private fun getBackgroundTrackingActivityRequirement(context: Context) =
			Preferences.getPref(context).getIntResString(
					R.string.settings_tracking_activity_key,
					R.string.settings_tracking_activity_default
			)

	private fun buildTransitions(context: Context): List<ActivityTransitionData> {
		val transitions = mutableListOf<ActivityTransitionData>()
		val requiredActivityId = getBackgroundTrackingActivityRequirement(context)

		if (requiredActivityId >= GroupedActivity.IN_VEHICLE.ordinal) {
			transitions.add(
					ActivityTransitionData(
							DetectedActivity.IN_VEHICLE,
							ActivityTransitionType.ENTER
					)
			)
			transitions.add(
					ActivityTransitionData(
							DetectedActivity.ON_BICYCLE,
							ActivityTransitionType.ENTER
					)
			)
		}

		if (requiredActivityId >= GroupedActivity.ON_FOOT.ordinal) {
			transitions.add(
					ActivityTransitionData(DetectedActivity.ON_FOOT, ActivityTransitionType.ENTER)
			)
			transitions.add(
					ActivityTransitionData(DetectedActivity.RUNNING, ActivityTransitionType.ENTER)
			)
			transitions.add(
					ActivityTransitionData(DetectedActivity.WALKING, ActivityTransitionType.ENTER)
			)
		}

		if (transitions.isNotEmpty()) {
			transitions.add(
					ActivityTransitionData(DetectedActivity.STILL, ActivityTransitionType.ENTER)
			)
		}

		return transitions
	}

	private fun getTransitions(context: Context): ActivityTransitionRequestData {
		val transitions = buildTransitions(context)
		return ActivityTransitionRequestData(transitions, transitionCallback)
	}

	private fun getActivityRequest(context: Context): ActivityChangeRequestData {
		val interval = Preferences.getPref(context)
				.getIntResString(
						R.string.settings_activity_freq_key,
						R.string.settings_activity_freq_default
				)
		return ActivityChangeRequestData(interval, callback)
	}

	private fun reinitializeRequest(context: Context, useTransitionApi: Boolean) {
		if (!isActive) return

		val requestData = if (useTransitionApi) {
			ActivityRequestData(this::class, transitionData = getTransitions(context))
		} else {
			ActivityRequestData(this::class, changeData = getActivityRequest(context))
		}

		ActivityRequestManager.requestActivity(context, requestData)
		ActivityWatcherService.poke(context)
	}

	private fun enable(context: Context) {
		if (isActive) return
		isActive = true

		val useTransitionApi = Preferences.getPref(context)
				.getBooleanRes(
						R.string.settings_auto_tracking_transition_key,
						R.string.settings_auto_tracking_transition_default
				)
		reinitializeRequest(context, useTransitionApi)
	}

	private fun disable(context: Context) {
		if (!isActive) return

		ActivityRequestManager.removeActivityRequest(context, this::class)
		ActivityWatcherService.poke(context)

		isActive = false
	}

	/**
	 * Initializes background tracking api.
	 */
	@MainThread
	fun initialize(context: Context) {
		if (appContext != null) return

		appContext = context.applicationContext

		PreferenceObserver.observe(
				context,
				R.string.settings_auto_tracking_transition_key,
				R.string.settings_auto_tracking_transition_default,
				transitionObserver
		)

		PreferenceObserver.observe(
				context,
				R.string.settings_tracking_activity_key,
				R.string.settings_tracking_activity_default,
				observer
		)
	}
}

