package com.adsamcik.tracker.tracker.api

import dev.tracebox.Tracebox
import android.content.Context
import androidx.annotation.MainThread
import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.backend.ActivityUpdate
import com.adsamcik.tracker.activity.api.backend.ActivityUpdateSource
import com.adsamcik.tracker.activity.api.backend.RecognizedActivity
import com.adsamcik.tracker.activity.api.backend.TransitionUpdate
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.adsamcik.tracker.shared.base.data.GroupedActivity
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.powerManager
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.source.runtime.AutomaticStartTransitionMonitor
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Hilt EntryPoint for accessing dependencies from BackgroundTrackingApi singleton
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundTrackingApiEntryPoint {
	fun automaticStartTransitionMonitor(): AutomaticStartTransitionMonitor
	fun lockManager(): LockManager
	fun trackerStateReader(): TrackerStateReader
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
	private var recognitionUpdatesJob: Job? = null
	private var requestMutationJob: Job? = null
	private var automaticStopGraceJob: Job? = null
	private var requestMutationGeneration = 0L
	private var automaticStopGeneration = 0L

	// Minimum confidence threshold for activity recognition
	// Future: Make configurable via settings (requires UI + preference storage)
	private const val REQUIRED_CONFIDENCE = 75

	// Lower confidence threshold accepted for ON_FOOT auto-start when the step counter independently
	// confirms recent walking. Only widens starts; never blocks one the full threshold would allow.
	private const val STEP_CORROBORATED_CONFIDENCE = 50
	private const val DEFAULT_ACTIVITY_FREQ_SECONDS = 10
	/** Allows a contradictory automatic-activity update to be corrected before a terminal stop. */
	private const val AUTOMATIC_STOP_GRACE_MILLIS = 30_000L
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

	private fun updateCachedParams(newParams: TrackingParamsState): TrackingParamsState =
		synchronized(paramsLock) {
			val previousParams = cachedParams
			cachedParams = newParams
			previousParams
		}

	private fun handleActivityUpdate(context: Context, activity: RecognizedActivity) {
		if (!context.hasActivityPermission) {
			cancelAutomaticStopGrace()
			// ACTIVITY_RECOGNITION was revoked while detection was armed. Tear the request down
			// instead of acting on a now-defunct subscription; it re-arms when re-granted.
			revalidatePermissions(context)
		} else if (TrackerServiceApi.isActive(context)) {
			// Stop evaluation is unchanged: only reconsider continuation at the full confidence
			// threshold so a borderline reading never tears down an active session.
			if (activity.confidence >= REQUIRED_CONFIDENCE) {
				val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value ?: run {
					cancelAutomaticStopGrace()
					return
				}
				when (
					resolveAutomaticTrackingContinuationAction(
						isUserInitiated = sessionInfo.isInitiatedByUser,
						canContinue = canContinueBackgroundTracking(activity.type.groupedActivity),
					)
				) {
					AutomaticTrackingContinuationAction.KEEP -> cancelAutomaticStopGrace()
					AutomaticTrackingContinuationAction.SCHEDULE_STOP_GRACE ->
						scheduleAutomaticStopGrace(context)
				}
			}
		} else {
			cancelAutomaticStopGrace()
			if (
				isOnFootAutoStartCorroborated(
					groupedActivity = activity.type.groupedActivity,
					confidence = activity.confidence,
					requiredConfidence = REQUIRED_CONFIDENCE,
					corroboratedConfidence = STEP_CORROBORATED_CONFIDENCE,
					hasRecentSteps = stepCorroborator.hasRecentSteps(),
				) &&
				canBackgroundTrack(context, activity.type.groupedActivity) &&
				canTrackerServiceBeStarted(context)
			) {
				TrackerServiceApi.startService(context, isUserInitiated = false)
			}
		}
	}

	private fun handleTransitionUpdate(context: Context, activity: ActivityTransitionData) {
		if (!context.hasActivityPermission) {
			cancelAutomaticStopGrace()
			revalidatePermissions(context)
		} else if (TrackerServiceApi.isActive(context)) {
			val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value ?: run {
				cancelAutomaticStopGrace()
				return
			}
			when (
				resolveAutomaticTrackingContinuationAction(
					isUserInitiated = sessionInfo.isInitiatedByUser,
					canContinue = canContinueBackgroundTracking(activity.activity.groupedActivity),
				)
			) {
				AutomaticTrackingContinuationAction.KEEP -> cancelAutomaticStopGrace()
				AutomaticTrackingContinuationAction.SCHEDULE_STOP_GRACE ->
					scheduleAutomaticStopGrace(context)
			}
		} else {
			cancelAutomaticStopGrace()
			if (canBackgroundTrack(context, activity.activity.groupedActivity) &&
				canTrackerServiceBeStarted(context)
			) {
				TrackerServiceApi.startService(context, isUserInitiated = false)
			}
		}
	}

	/** Replays a post-admission activity effect; safe to invoke more than once after a crash. */
	internal fun handleDurableActivityEvidence(
		context: Context,
		activity: DetectedActivityType,
		confidence: Int,
		transitionType: ActivityTransitionType?,
	) {
		if (transitionType == null) {
			handleActivityUpdate(context.applicationContext, RecognizedActivity(activity, confidence))
		} else {
			handleTransitionUpdate(
				context.applicationContext,
				ActivityTransitionData(activity, transitionType),
			)
		}
	}

	/**
	 * Stops only after a short contradiction window.  The eventual service stop persists an
	 * [TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE] descriptor before teardown;
	 * a compatible follow-up cancels this job and leaves the logical session active.
	 */
	private fun scheduleAutomaticStopGrace(context: Context) {
		if (automaticStopGraceJob?.isActive == true) return
		val scope = preferenceScope ?: return
		val generation = ++automaticStopGeneration
		val appContext = context.applicationContext
		automaticStopGraceJob = scope.launch {
			delay(AUTOMATIC_STOP_GRACE_MILLIS)
			if (generation != automaticStopGeneration) return@launch
			automaticStopGraceJob = null
			if (!TrackerServiceApi.isActive(appContext)) return@launch
			val sessionInfo = TrackerServiceApi.sessionInfoFlow(appContext).value ?: return@launch
			if (!sessionInfo.isInitiatedByUser) {
				TrackerServiceApi.stopService(
					appContext,
					TrackingStopCandidateReason.AUTOMATIC_ACTIVITY_INCOMPATIBLE,
				)
			}
		}
	}

	private fun cancelAutomaticStopGrace() {
		automaticStopGeneration++
		automaticStopGraceJob?.cancel()
		automaticStopGraceJob = null
	}

	private fun canTrackerServiceBeStarted(context: Context): Boolean {
		val entryPoint = getEntryPoint(context)
		val capabilities = context.trackingPermissionCapabilities()
		return !entryPoint.lockManager().isLocked &&
			!context.powerManager.isPowerSaveMode &&
			isAutomaticStartEligible(
				params = cachedParamsSnapshot(),
				locationAvailable = capabilities.hasForegroundLocation,
				backgroundLocationAvailable = capabilities.hasBackgroundLocation,
				activityAvailable = context.hasActivityPermission,
				stepsAvailable = context.hasActivityPermission && context.hasStepCounterSensor,
				wifiAvailable = capabilities.hasWifiScan,
				cellAvailable = context.hasCellScanPermission,
				barometerAvailable = context.hasPressureSensor,
			)
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
		val isTrackerRunning = entryPoint.trackerStateReader().isServiceRunning
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
					DetectedActivityType.IN_VEHICLE,
					ActivityTransitionType.ENTER
				)
			)
			transitions.add(
				ActivityTransitionData(
					DetectedActivityType.ON_BICYCLE,
					ActivityTransitionType.ENTER
				)
			)
		}

		if (requiredActivityId >= GroupedActivity.ON_FOOT.ordinal) {
			transitions.add(
				ActivityTransitionData(DetectedActivityType.ON_FOOT, ActivityTransitionType.ENTER)
			)
			transitions.add(
				ActivityTransitionData(DetectedActivityType.RUNNING, ActivityTransitionType.ENTER)
			)
			transitions.add(
				ActivityTransitionData(DetectedActivityType.WALKING, ActivityTransitionType.ENTER)
			)
		}

		if (transitions.isNotEmpty()) {
			transitions.add(
				ActivityTransitionData(DetectedActivityType.STILL, ActivityTransitionType.ENTER)
			)
		}

		return transitions
	}

	private fun reinitializeRequest(context: Context, useTransitionApi: Boolean) {
		val generation = ++requestMutationGeneration
		val monitor = getEntryPoint(context).automaticStartTransitionMonitor()
		val watcherController = getWatcherController(context)
		enqueueRequestMutation {
			try {
				val result = monitor.reconcile(
					enabled = true,
					useTransitionApi = useTransitionApi,
					continuousIntervalSeconds = activityFreqSeconds,
					transitions = buildTransitions().toSet(),
				)
				check(result.status == ActivityRegistrationStatus.APPLIED ||
					result.status == ActivityRegistrationStatus.DEGRADED
				) { "Unable to apply background activity recognition request: ${result.failureCode}" }
				if (generation != requestMutationGeneration || !isActive) {
					return@enqueueRequestMutation
				}

				// Activity callbacks are consumed from durable projection outbox. Keep the optional
				// step corroborator only for confidence-based recognition requests.
				if (useTransitionApi) stepCorroborator.stop(context) else stepCorroborator.start(context)
				recognitionUpdatesJob?.cancel()
				recognitionUpdatesJob = null
				watcherController.poke()
			} catch (exception: CancellationException) {
				throw exception
			} catch (exception: Exception) {
				if (generation == requestMutationGeneration && isActive) {
					isActive = false
					val cleanupGeneration = ++requestMutationGeneration
					recognitionUpdatesJob?.cancel()
					recognitionUpdatesJob = null
					stepCorroborator.stop(context)
					var cleanupFailureAttached = false
					val removed = reconcileActivityRequestRemoval(
						shouldContinue = {
							cleanupGeneration == requestMutationGeneration && !isActive
						},
						onFailure = { cleanupFailure ->
							if (!cleanupFailureAttached) {
								exception.addSuppressed(cleanupFailure)
								cleanupFailureAttached = true
							}
						},
					) {
						val cleanup = monitor.reconcile(
							enabled = false,
							useTransitionApi = false,
							continuousIntervalSeconds = activityFreqSeconds,
							transitions = emptySet(),
						)
						check(cleanup.status != ActivityRegistrationStatus.FAILED) {
							"Unable to clear automatic activity demand: ${cleanup.failureCode}"
						}
					}
					if (removed) watcherController.poke()
				}
				throw exception
			}
		}
	}

	private fun getWatcherController(context: Context): ActivityWatcherController =
		getEntryPoint(context).activityWatcherController()

	private fun enable(context: Context) {
		isActive = true
		reinitializeRequest(context, cachedParamsSnapshot().transitionDetectionEnabled)
	}

	private fun disable(context: Context) {
		isActive = false
		cancelAutomaticStopGrace()
		val generation = ++requestMutationGeneration

		val monitor = getEntryPoint(context).automaticStartTransitionMonitor()
		val watcherController = getWatcherController(context)
		enqueueRequestMutation {
			if (generation != requestMutationGeneration || isActive) {
				return@enqueueRequestMutation
			}
			recognitionUpdatesJob?.cancel()
			recognitionUpdatesJob = null
			stepCorroborator.stop(context)
			val removed = reconcileActivityRequestRemoval(
				shouldContinue = { generation == requestMutationGeneration && !isActive },
			) {
				val result = monitor.reconcile(
					enabled = false,
					useTransitionApi = false,
					continuousIntervalSeconds = activityFreqSeconds,
					transitions = emptySet(),
				)
				check(result.status != ActivityRegistrationStatus.FAILED) {
					"Unable to clear automatic activity demand: ${result.failureCode}"
				}
			}
			if (removed) watcherController.poke()
		}
	}

	private fun enqueueRequestMutation(block: suspend () -> Unit) {
		val scope = requireNotNull(preferenceScope)
		val previousMutation = requestMutationJob
		requestMutationJob = scope.launch {
			previousMutation?.join()
			try {
				block()
			} catch (e: CancellationException) {
				throw e
			} catch (error: Exception) {
				Tracebox.log.error(error, "Activity recognition failed")
			}
		}
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
			.catch { error ->
				Tracebox.log.error(error, "Application initialization failed")
			}
			.launchIn(scope)

		disabledRechargeJob = PreferenceFlows.boolean(
			ctx,
			R.string.settings_disabled_recharge_key,
			R.string.settings_disabled_recharge_default
		).onEach { disabledUntilRecharge = it }
			.catch { error ->
				Tracebox.log.error(error, "Application initialization failed")
			}
			.launchIn(scope)

		activityFreqJob = PreferenceFlows.intFromString(
			ctx,
			com.adsamcik.tracker.activity.R.string.settings_activity_freq_key,
			com.adsamcik.tracker.activity.R.string.settings_activity_freq_default
		).onEach { frequencySeconds ->
			val changed = frequencySeconds != activityFreqSeconds
			activityFreqSeconds = frequencySeconds
			if (changed && isActive) {
				reinitializeRequest(ctx, cachedParamsSnapshot().transitionDetectionEnabled)
			} else {
				getWatcherController(ctx).poke()
			}
		}
			.catch { error ->
				Tracebox.log.error(error, "Application initialization failed")
			}
			.launchIn(scope)

		activityWatcherJob = PreferenceFlows.boolean(
			ctx,
			com.adsamcik.tracker.activity.R.string.settings_activity_watcher_key,
			com.adsamcik.tracker.activity.R.string.settings_activity_watcher_default
		).onEach { activityWatcherEnabled = it }
			.catch { error ->
				Tracebox.log.error(error, "Application initialization failed")
			}
			.launchIn(scope)
	}

	private fun handleTrackingActivityPreferenceChange(value: Int) {
		val context = appContext ?: return
		// A preference change changes the continuation predicate, so an old incompatible reading
		// must never finish a grace timer under a different policy.
		cancelAutomaticStopGrace()
		when (resolveAutoTrackingPreferenceAction(value, isActive, context.hasActivityPermission)) {
			AutoTrackingPreferenceAction.DISABLE -> disable(context)
			AutoTrackingPreferenceAction.ENABLE -> enable(context)
			AutoTrackingPreferenceAction.REINITIALIZE ->
				reinitializeRequest(context, cachedParamsSnapshot().transitionDetectionEnabled)
			AutoTrackingPreferenceAction.NONE -> Unit
		}
		if (value == GroupedActivity.STILL.ordinal && TrackerServiceApi.isActive(context)) {
			val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value
			if (shouldStopSessionWhenAutoTrackingDisabled(value, sessionInfo?.isInitiatedByUser)) {
				TrackerServiceApi.stopService(context, TrackingStopCandidateReason.EXPLICIT_REQUEST)
			}
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
		recognitionUpdatesJob?.cancel()
		recognitionUpdatesJob = null
		cancelAutomaticStopGrace()
		val scope = preferenceScope
		val finalMutation = requestMutationJob
		if (scope != null) {
			if (finalMutation == null) {
				scope.cancel()
			} else {
				finalMutation.invokeOnCompletion { scope.cancel() }
			}
		}
		requestMutationJob = null
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

internal suspend fun reconcileActivityRequestRemoval(
	initialRetryDelayMillis: Long = 500L,
	maxRetryDelayMillis: Long = 5_000L,
	maxAttempts: Int = 6,
	shouldContinue: () -> Boolean,
	onFailure: (Exception) -> Unit = {},
	remove: suspend () -> Unit,
): Boolean {
	require(initialRetryDelayMillis >= 0L)
	require(maxRetryDelayMillis >= initialRetryDelayMillis)
	require(maxAttempts > 0)

	var retryDelayMillis = initialRetryDelayMillis
	repeat(maxAttempts) { attempt ->
		if (!shouldContinue()) return false
		try {
			remove()
			return true
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			onFailure(exception)
			if (!shouldContinue()) return false
			if (attempt < maxAttempts - 1) {
				delay(retryDelayMillis)
				retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(maxRetryDelayMillis)
			}
		}
	}
	return false
}

private fun ActivityTransitionData.matches(update: TransitionUpdate): Boolean =
	activity == update.activityType && type == update.transitionType

private val DetectedActivityType.groupedActivity: GroupedActivity
	get() = when (this) {
		DetectedActivityType.STILL -> GroupedActivity.STILL
		DetectedActivityType.WALKING,
		DetectedActivityType.RUNNING,
		DetectedActivityType.ON_FOOT,
		-> GroupedActivity.ON_FOOT
		DetectedActivityType.ON_BICYCLE,
		DetectedActivityType.IN_VEHICLE,
		-> GroupedActivity.IN_VEHICLE
		DetectedActivityType.TILTING,
		DetectedActivityType.UNKNOWN,
		-> GroupedActivity.UNKNOWN
	}

internal fun selectNewestConfiguredTransition(
	configuredTransitions: Collection<ActivityTransitionData>,
	updates: List<TransitionUpdate>,
): ActivityTransitionData? = updates.withIndex()
	.mapNotNull { (index, update) ->
		configuredTransitions.firstOrNull { it.matches(update) }
			?.let { transition -> Triple(update.elapsedRealTimeNanos, index, transition) }
	}
	.maxWithOrNull(compareBy<Triple<Long, Int, ActivityTransitionData>>({ it.first }, { it.second }))
	?.third

internal fun isChangeDetectionUpdate(update: ActivityUpdate): Boolean =
	update.source == ActivityUpdateSource.RECOGNITION

/** Pure logic: checks if at least one available capture source is enabled. */
internal fun hasAnythingToTrack(
	params: TrackingParamsState,
	locationAvailable: Boolean = true,
	activityAvailable: Boolean = true,
	stepsAvailable: Boolean = true,
	wifiAvailable: Boolean = true,
	cellAvailable: Boolean = true,
	barometerAvailable: Boolean = true,
): Boolean = params.hasAnyCaptureSource(
	locationAvailable = locationAvailable,
	activityAvailable = activityAvailable,
	stepsAvailable = stepsAvailable,
	wifiAvailable = wifiAvailable,
	cellAvailable = cellAvailable,
	barometerAvailable = barometerAvailable,
)

/**
 * An automatic callback is a background start. If its configured plan includes location, Android
 * 10+ background access must be effective before dispatching the service; foreground/coarse access
 * alone remains valid for a later user-initiated manual session.
 */
internal fun isAutomaticStartEligible(
	params: TrackingParamsState,
	locationAvailable: Boolean,
	backgroundLocationAvailable: Boolean,
	activityAvailable: Boolean = true,
	stepsAvailable: Boolean = true,
	wifiAvailable: Boolean = true,
	cellAvailable: Boolean = true,
	barometerAvailable: Boolean = true,
): Boolean {
	if (params.locationEnabled && (!locationAvailable || !backgroundLocationAvailable)) return false
	return hasAnythingToTrack(
		params = params,
		locationAvailable = locationAvailable,
		activityAvailable = activityAvailable,
		stepsAvailable = stepsAvailable,
		wifiAvailable = wifiAvailable,
		cellAvailable = cellAvailable,
		barometerAvailable = barometerAvailable,
	)
}

/** Action to take when the auto-tracking activity requirement preference changes. */
internal enum class AutoTrackingPreferenceAction { NONE, ENABLE, DISABLE, REINITIALIZE }

/** The automatic-tracking controller never tears down a user session from activity recognition. */
internal enum class AutomaticTrackingContinuationAction { KEEP, SCHEDULE_STOP_GRACE }

internal fun resolveAutomaticTrackingContinuationAction(
	isUserInitiated: Boolean,
	canContinue: Boolean,
): AutomaticTrackingContinuationAction = if (isUserInitiated || canContinue) {
	AutomaticTrackingContinuationAction.KEEP
} else {
	AutomaticTrackingContinuationAction.SCHEDULE_STOP_GRACE
}

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

/** Turning automation off ends an automatic session but never tears down a manual one. */
internal fun shouldStopSessionWhenAutoTrackingDisabled(
	newMode: Int,
	isUserInitiated: Boolean?,
): Boolean = newMode == GroupedActivity.STILL.ordinal && isUserInitiated == false

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
