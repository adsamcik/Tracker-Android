package com.adsamcik.tracker.tracker.api

import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import com.adsamcik.tracker.activity.ActivityChangeRequestCallback
import com.adsamcik.tracker.activity.ActivityChangeRequestData
import com.adsamcik.tracker.activity.ActivityRequestData
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionRequestCallback
import com.adsamcik.tracker.activity.ActivityTransitionRequestData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.ActivityRequestManager
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.logger.assertFalse
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.adsamcik.tracker.logger.assertTrue
import com.adsamcik.tracker.shared.base.data.DetectedActivity
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.powerManager
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Hilt EntryPoint for accessing dependencies from BackgroundTrackingApi singleton
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundTrackingApiEntryPoint {
	fun activityRequestManager(): ActivityRequestManager
	fun lockManager(): LockManager
	fun trackerServiceController(): TrackerServiceController
	fun trackingParamsRepository(): TrackingParamsRepository
	fun activityWatcherController(): ActivityWatcherController
}

/**
 * Exposed methods for background tracking
 */
@Suppress("TooManyFunctions")
object BackgroundTrackingApi {
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider
	var isActive: Boolean = false
		private set

	private var preferenceScope: CoroutineScope? = null
	private var trackingParamsJob: Job? = null
	private var disabledRechargeJob: Job? = null
	private var activityFreqJob: Job? = null
	private var activityWatcherJob: Job? = null

	// Minimum confidence threshold for activity recognition
	// Future: Make configurable via settings (requires UI + preference storage)
	private const val REQUIRED_CONFIDENCE = 75
	private const val DEFAULT_ACTIVITY_FREQ_SECONDS = 10
	private const val TAG = "BackgroundTrackingApi"
	private var appContext: Context? = null
	@Volatile
	private var entryPoint: BackgroundTrackingApiEntryPoint? = null
	private val paramsLock = Any()

	/** Cached tracking parameters from DataStore. Updated via Flow observation. */
	@Volatile
	var cachedParams = TrackingParamsState()
		private set

	/** Cached disabled-until-recharge flag. Updated via Flow observation. */
	@Volatile
	var disabledUntilRecharge = false
		private set

	/** Cached activity recognition polling interval in seconds. */
	@Volatile
	var activityFreqSeconds = DEFAULT_ACTIVITY_FREQ_SECONDS
		private set

	/** Cached activity watcher enabled preference. */
	@Volatile
	var activityWatcherEnabled = false
		private set

	/** Whether the first TrackingParams emission has been processed. */
	private var paramsInitialized = false

	private fun getEntryPoint(context: Context): BackgroundTrackingApiEntryPoint {
		val cachedEntryPoint = entryPoint
		if (cachedEntryPoint != null) return cachedEntryPoint
		return synchronized(this) {
			entryPoint ?: EntryPointAccessors.fromApplication(
				context.applicationContext,
				BackgroundTrackingApiEntryPoint::class.java
			).also { entryPoint = it }
		}
	}

	private fun cachedParamsSnapshot(): TrackingParamsState = synchronized(paramsLock) { cachedParams }

	private fun activityRequestManager(context: Context): ActivityRequestManager =
		getEntryPoint(context).activityRequestManager()

	private fun updateCachedParams(newParams: TrackingParamsState): TrackingParamsState =
		synchronized(paramsLock) {
			val previousParams = cachedParams
			cachedParams = newParams
			previousParams
		}

	// Activity change callback for automatic tracking control
	// Future: Expose callback configuration in advanced settings
	private val callback: ActivityChangeRequestCallback = { context, activity, _ ->
		if (activity.confidence >= REQUIRED_CONFIDENCE) {
			if (TrackerServiceApi.isActive(context)) {
				val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value
				if (!requireNotNull(sessionInfo).isInitiatedByUser &&
					!canContinueBackgroundTracking(activity.groupedActivity)
				) {
					TrackerServiceApi.stopService(context)
				}
			} else {
				if (canBackgroundTrack(context, activity.groupedActivity) &&
					canTrackerServiceBeStarted(context)
				) {
					TrackerServiceApi.startService(context, isUserInitiated = false)
				}
			}
		}
	}

	private val transitionCallback: ActivityTransitionRequestCallback = { context, activity, _ ->
		if (TrackerServiceApi.isActive(context)) {
			val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value
			if (!requireNotNull(sessionInfo).isInitiatedByUser &&
				!canContinueBackgroundTracking(activity.activity.groupedActivity)
			) {
				TrackerServiceApi.stopService(context)
			}
		} else {
			if (canBackgroundTrack(context, activity.activity.groupedActivity) &&
				canTrackerServiceBeStarted(context)
			) {
				TrackerServiceApi.startService(context, isUserInitiated = false)
			}
		}
	}

	private fun canTrackerServiceBeStarted(context: Context): Boolean {
		val entryPoint = getEntryPoint(context)
		return !entryPoint.lockManager().isLocked &&
			!context.powerManager.isPowerSaveMode &&
			hasAnythingToTrack(cachedParamsSnapshot())
	}

	/**
	 * Checks if background tracking can be activated
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can be activated
	 */
	private fun canBackgroundTrack(context: Context, groupedActivity: GroupedActivity): Boolean {
		val entryPoint = getEntryPoint(context)
		val params = cachedParamsSnapshot()
		val isTrackerRunning = entryPoint.trackerServiceController().isServiceRunning
		return canBackgroundTrackWithParams(
			groupedActivity = groupedActivity,
			isTrackerRunning = isTrackerRunning,
			disabledUntilRecharge = disabledUntilRecharge,
			autoTrackingMode = params.autoTrackingMode,
		)
	}

	/**
	 * Checks if background tracking should stop.
	 *
	 * @param groupedActivity evaluated activity
	 * @return true if background tracking can continue running
	 */
	private fun canContinueBackgroundTracking(
		groupedActivity: GroupedActivity,
	): Boolean = canContinueWithParams(groupedActivity, cachedParamsSnapshot().autoTrackingMode)

	private fun buildTransitions(): List<ActivityTransitionData> {
		val transitions = mutableListOf<ActivityTransitionData>()
		val requiredActivityId = cachedParamsSnapshot().autoTrackingMode

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

	private fun getTransitions(): ActivityTransitionRequestData {
		val transitions = buildTransitions()
		return ActivityTransitionRequestData(transitions, transitionCallback)
	}

	private fun getActivityRequest(): ActivityChangeRequestData {
		return ActivityChangeRequestData(activityFreqSeconds, callback)
	}

	private fun reinitializeRequest(context: Context, useTransitionApi: Boolean) {
		assertTrue(isActive)

		val requestData = if (useTransitionApi) {
			ActivityRequestData(this::class, transitionData = getTransitions())
		} else {
			ActivityRequestData(this::class, changeData = getActivityRequest())
		}

		activityRequestManager(context).requestActivity(context, requestData)
		getWatcherController(context).poke()
	}

	private fun getWatcherController(context: Context): ActivityWatcherController =
		getEntryPoint(context).activityWatcherController()

	private fun enable(context: Context) {
		assertFalse(isActive)
		isActive = true
		reinitializeRequest(context, cachedParamsSnapshot().transitionDetectionEnabled)
	}

	private fun disable(context: Context) {
		assertTrue(isActive)

		activityRequestManager(context).removeActivityRequest(context, this::class)
		getWatcherController(context).poke()

		isActive = false
	}

	/**
	 * Initializes background tracking api.
	 */
	@MainThread
	fun initialize(context: Context) {
		synchronized(this) {
			if (appContext != null) return
			appContext = context.applicationContext
		}
		val ctx = requireNotNull(appContext)

		val entryPoint = getEntryPoint(ctx)

		val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
		val scope = CoroutineScope(SupervisorJob() + mainImmediate)
		preferenceScope = scope

		trackingParamsJob = entryPoint.trackingParamsRepository().data
			.onEach { params ->
				val previousParams = updateCachedParams(params)
				if (!paramsInitialized ||
					params.autoTrackingMode != previousParams.autoTrackingMode
				) {
					handleTrackingActivityPreferenceChange(params.autoTrackingMode)
				}
				if (!paramsInitialized ||
					params.transitionDetectionEnabled != previousParams.transitionDetectionEnabled
				) {
					handleTransitionPreferenceChange(params.transitionDetectionEnabled)
				}
				paramsInitialized = true
			}
			.catch { throwable ->
				Log.e(TAG, "Tracking params flow collection failed", throwable)
			}
			.launchIn(scope)

		disabledRechargeJob = PreferenceFlows.boolean(
			ctx,
			R.string.settings_disabled_recharge_key,
			R.string.settings_disabled_recharge_default
		).onEach { disabledUntilRecharge = it }
			.catch { throwable ->
				Log.e(TAG, "Disabled recharge flow collection failed", throwable)
			}
			.launchIn(scope)

		activityFreqJob = PreferenceFlows.intFromString(
			ctx,
			com.adsamcik.tracker.activity.R.string.settings_activity_freq_key,
			com.adsamcik.tracker.activity.R.string.settings_activity_freq_default
		).onEach { activityFreqSeconds = it }
			.catch { throwable ->
				Log.e(TAG, "Activity frequency flow collection failed", throwable)
			}
			.launchIn(scope)

		activityWatcherJob = PreferenceFlows.boolean(
			ctx,
			com.adsamcik.tracker.activity.R.string.settings_activity_watcher_key,
			com.adsamcik.tracker.activity.R.string.settings_activity_watcher_default
		).onEach { activityWatcherEnabled = it }
			.catch { throwable ->
				Log.e(TAG, "Activity watcher flow collection failed", throwable)
			}
			.launchIn(scope)
	}

	private fun handleTrackingActivityPreferenceChange(value: Int) {
		val context = appContext ?: return
		if (value == GroupedActivity.STILL.ordinal && isActive) {
			disable(context)
		} else if (!isActive && context.hasActivityPermission) {
			enable(context)
		}
	}

	private fun handleTransitionPreferenceChange(enabled: Boolean) {
		val context = appContext ?: return
		if (isActive) {
			reinitializeRequest(context, enabled)
		}
	}

	/**
	 * Shuts down the BackgroundTrackingApi, cancelling all coroutines and releasing resources.
	 * Should be called when the API is no longer needed (e.g., in Application.onTerminate for testing
	 * or when explicitly shutting down background tracking functionality).
	 */
	@MainThread
	fun shutdown() {
		val context = appContext
		if (context != null && isActive) {
			disable(context)
		}

		trackingParamsJob?.cancel()
		trackingParamsJob = null
		disabledRechargeJob?.cancel()
		disabledRechargeJob = null
		activityFreqJob?.cancel()
		activityFreqJob = null
		activityWatcherJob?.cancel()
		activityWatcherJob = null
		preferenceScope?.cancel()
		preferenceScope = null
		appContext = null
		entryPoint = null
		synchronized(paramsLock) {
			cachedParams = TrackingParamsState()
		}
		disabledUntilRecharge = false
		activityFreqSeconds = DEFAULT_ACTIVITY_FREQ_SECONDS
		activityWatcherEnabled = false
		paramsInitialized = false
	}
}

/** Pure logic: checks if at least one trackable data source is enabled. */
internal fun hasAnythingToTrack(params: TrackingParamsState): Boolean =
	params.locationEnabled || params.cellEnabled ||
		params.wifiEnabled || params.wifiLocationCountEnabled || params.wifiNetworkEnabled ||
		params.activityEnabled || params.stepsEnabled

/** Pure logic: checks if background tracking can be activated for the given activity and preferences. */
internal fun canBackgroundTrackWithParams(
	groupedActivity: GroupedActivity,
	isTrackerRunning: Boolean,
	disabledUntilRecharge: Boolean,
	autoTrackingMode: Int,
): Boolean {
	if (groupedActivity.isStillOrUnknown || isTrackerRunning || disabledUntilRecharge) {
		return false
	}
	val prefActivity = GroupedActivity.values()[autoTrackingMode]
	return prefActivity != GroupedActivity.STILL &&
		(prefActivity == groupedActivity || prefActivity.ordinal > groupedActivity.ordinal)
}

/** Pure logic: checks if background tracking should continue for the given activity. */
internal fun canContinueWithParams(
	groupedActivity: GroupedActivity,
	autoTrackingMode: Int,
): Boolean {
	if (groupedActivity == GroupedActivity.STILL) return false
	val prefActivity = GroupedActivity.values()[autoTrackingMode]
	return prefActivity == GroupedActivity.IN_VEHICLE ||
		(prefActivity == GroupedActivity.ON_FOOT &&
			(groupedActivity == GroupedActivity.ON_FOOT || groupedActivity == GroupedActivity.UNKNOWN))
}
