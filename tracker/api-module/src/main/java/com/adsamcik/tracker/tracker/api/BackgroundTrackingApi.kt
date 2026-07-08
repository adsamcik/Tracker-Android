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

	// Lower confidence threshold accepted for ON_FOOT auto-start when the step counter independently
	// confirms recent walking. Only widens starts; never blocks one the full threshold would allow.
	private const val STEP_CORROBORATED_CONFIDENCE = 50
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

	/**
	 * Corroborates lower-confidence ON_FOOT detections with the hardware step counter. Active only
	 * while the confidence-based change-detection API is registered.
	 */
	private val stepCorroborator = StepActivityCorroborator()

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
		if (!context.hasActivityPermission) {
			// ACTIVITY_RECOGNITION was revoked while detection was armed. Tear the request down
			// instead of acting on a now-defunct subscription; it re-arms when re-granted.
			revalidatePermissions(context)
		} else if (TrackerServiceApi.isActive(context)) {
			// Stop evaluation is unchanged: only reconsider continuation at the full confidence
			// threshold so a borderline reading never tears down an active session.
			if (activity.confidence >= REQUIRED_CONFIDENCE) {
				val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value
				if (!requireNotNull(sessionInfo).isInitiatedByUser &&
					!canContinueBackgroundTracking(activity.groupedActivity)
				) {
					TrackerServiceApi.stopService(context)
				}
			}
		} else if (
			isOnFootAutoStartCorroborated(
				groupedActivity = activity.groupedActivity,
				confidence = activity.confidence,
				requiredConfidence = REQUIRED_CONFIDENCE,
				corroboratedConfidence = STEP_CORROBORATED_CONFIDENCE,
				hasRecentSteps = stepCorroborator.hasRecentSteps(),
			) &&
			canBackgroundTrack(context, activity.groupedActivity) &&
			canTrackerServiceBeStarted(context)
		) {
			TrackerServiceApi.startService(context, isUserInitiated = false)
		}
	}

	private val transitionCallback: ActivityTransitionRequestCallback = { context, activity, _ ->
		if (!context.hasActivityPermission) {
			revalidatePermissions(context)
		} else if (TrackerServiceApi.isActive(context)) {
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
			// Transition API is already high-confidence; the step corroborator only helps the
			// confidence-based change API, so release the sensor while transitions are used.
			stepCorroborator.stop(context)
			ActivityRequestData(this::class, transitionData = getTransitions())
		} else {
			stepCorroborator.start(context)
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
		stepCorroborator.stop(context)
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
		when (resolveAutoTrackingPreferenceAction(value, isActive, context.hasActivityPermission)) {
			AutoTrackingPreferenceAction.DISABLE -> disable(context)
			AutoTrackingPreferenceAction.ENABLE -> enable(context)
			AutoTrackingPreferenceAction.REINITIALIZE ->
				reinitializeRequest(context, cachedParamsSnapshot().transitionDetectionEnabled)
			AutoTrackingPreferenceAction.NONE -> Unit
		}
	}

	private fun handleTransitionPreferenceChange(enabled: Boolean) {
		val context = appContext ?: return
		if (isActive) {
			reinitializeRequest(context, enabled)
		}
	}

	/**
	 * Reconciles the detection state with the current ACTIVITY_RECOGNITION permission.
	 *
	 * Android usually kills the process when a runtime permission is revoked, but not always
	 * (unused-app auto-reset, appops changes, future OS behaviour). When the permission is lost while
	 * detection is armed this tears the now-defunct activity-recognition subscription down; when it is
	 * granted again and automatic tracking is still enabled it re-arms detection. No-op before
	 * initialization and idempotent.
	 *
	 * Called when the app returns to the foreground (process lifecycle) and defensively from the
	 * activity-recognition callbacks. Must run on the main thread because it touches enable/disable.
	 */
	@MainThread
	fun revalidatePermissions(context: Context) {
		val ctx = appContext ?: return
		when (
			resolveDetectionPermissionAction(
				isActive = isActive,
				hasActivityPermission = ctx.hasActivityPermission,
				autoTrackingMode = cachedParamsSnapshot().autoTrackingMode,
			)
		) {
			AutoTrackingPreferenceAction.DISABLE -> disable(ctx)
			AutoTrackingPreferenceAction.ENABLE -> enable(ctx)
			AutoTrackingPreferenceAction.REINITIALIZE,
			AutoTrackingPreferenceAction.NONE,
			-> Unit
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

/** Action to take when the auto-tracking activity requirement preference changes. */
internal enum class AutoTrackingPreferenceAction { NONE, ENABLE, DISABLE, REINITIALIZE }

/**
 * Pure logic: reconciles detection state with the current ACTIVITY_RECOGNITION permission.
 *
 * Unlike [resolveAutoTrackingPreferenceAction] (which reacts to preference changes and never assumes
 * permission loss), this focuses on runtime permission revocation/grant: it disables an active
 * detection that has lost the permission, and re-arms detection that was previously stopped once the
 * permission is granted again and a movement mode is still selected.
 */
internal fun resolveDetectionPermissionAction(
	isActive: Boolean,
	hasActivityPermission: Boolean,
	autoTrackingMode: Int,
): AutoTrackingPreferenceAction {
	val shouldDetect = hasActivityPermission && autoTrackingMode != GroupedActivity.STILL.ordinal
	return when {
		isActive && !hasActivityPermission -> AutoTrackingPreferenceAction.DISABLE
		!isActive && shouldDetect -> AutoTrackingPreferenceAction.ENABLE
		else -> AutoTrackingPreferenceAction.NONE
	}
}

/**
 * Pure logic: resolves how to react to an auto-tracking activity-requirement change.
 *
 * [REINITIALIZE] covers the previously-missed case where the requirement changes between
 * two movement modes (e.g. ON_FOOT -> IN_VEHICLE) while the watcher is already active: the
 * underlying activity-recognition request/transitions must be re-registered so the new mode
 * actually takes effect instead of silently keeping the old subscription.
 */
internal fun resolveAutoTrackingPreferenceAction(
	newMode: Int,
	isActive: Boolean,
	hasActivityPermission: Boolean,
): AutoTrackingPreferenceAction {
	val isStill = newMode == GroupedActivity.STILL.ordinal
	return when {
		isStill && isActive -> AutoTrackingPreferenceAction.DISABLE
		!isActive && hasActivityPermission -> AutoTrackingPreferenceAction.ENABLE
		isActive && !isStill -> AutoTrackingPreferenceAction.REINITIALIZE
		else -> AutoTrackingPreferenceAction.NONE
	}
}

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
