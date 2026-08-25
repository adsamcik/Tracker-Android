package com.adsamcik.tracker.tracker.api

import dev.tracebox.Tracebox
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.base.extension.powerManager
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartContext
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.source.runtime.AutomaticStartTransitionMonitor
import com.adsamcik.tracker.tracker.source.runtime.SharedStepSourceController
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.service.ForegroundSourceCapabilities
import com.adsamcik.tracker.tracker.service.acceptedForegroundSources
import com.adsamcik.tracker.tracker.service.configuredForegroundSources
import com.adsamcik.tracker.tracker.service.foregroundServiceTypeCandidates
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hilt EntryPoint for accessing dependencies from BackgroundTrackingApi singleton
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundTrackingApiEntryPoint {
	fun automaticStartTransitionMonitor(): AutomaticStartTransitionMonitor
	fun sharedStepSourceController(): SharedStepSourceController
	fun lockManager(): LockManager
	fun trackerStateReader(): TrackerStateReader
	fun trackingParamsRepository(): TrackingParamsRepository
	fun sourcePolicyRepository(): SourcePolicyRepository
	fun activityWatcherController(): ActivityWatcherController
	fun automaticControlRecoveryScheduler(): AutomaticControlRecoveryScheduler
	fun trackingStartupGate(): TrackingStartupGate
	fun trackingRolloutStateStore(): RoomTrackingRolloutStateStore
}

/** WorkManager disposition for restoring the optional automatic Activity control registration. */
enum class AutomaticControlRecoveryResult {
	ACCEPTED,
	/** Control is disabled, revoked, permission-ineligible, or deliberately rollout-contained. */
	TERMINAL_DISABLED_OR_CONTAINED,
	/** Storage, startup, provider registration, or cleanup may succeed on a later bounded attempt. */
	RETRYABLE,
}

/** Durable, unique retry owner for transient automatic-control and projection recovery. */
fun interface AutomaticControlRecoveryScheduler {
	fun enqueue()
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
	private var sourcePolicyJob: Job? = null
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
	private const val SOURCE_POLICY_RETRY_DELAY_MILLIS = 250L
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

	/** Whether an authoritative TrackingParams emission has been processed. */
	@Volatile
	private var paramsInitialized = false
	/** Active SourcePolicy revision. Null is deliberately fail-closed. */
	@Volatile
	private var activeSourcePolicyRevision: Long? = null
	@Volatile
	private var activityControlEligible = false
	@Volatile
	private var activityControlConsentEpoch: Long? = null
	@Volatile
	private var stepControlEligible = false
	@Volatile
	private var activityAutomationAuthority = ActivityAutomationAuthoritySnapshot()
	private val _activityAutomationAuthorityReady = MutableStateFlow(false)

	/**
	 * True only while SourcePolicy and TrackingParams describe the same effective revision. The
	 * app-owned outbox driver observes this fence; durable effects remain pending while it is false.
	 */
	internal val activityAutomationAuthorityReady: StateFlow<Boolean> =
		_activityAutomationAuthorityReady.asStateFlow()

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

	private suspend fun handleActivityUpdate(
		context: Context,
		activity: RecognizedActivity,
		startContext: ActivityAutomationStartContext,
		automaticTrigger: AutomaticTrackingStartTrigger?,
		requestAutomaticStart: suspend (AutomaticTrackingStartTrigger) -> ActivityAutomationDeliveryResult,
	): ActivityAutomationDeliveryResult {
		if (!context.hasActivityPermission) {
			cancelAutomaticStopGrace()
			// ACTIVITY_RECOGNITION was revoked while detection was armed. Tear the request down
			// instead of acting on a now-defunct subscription; it re-arms when re-granted.
			revalidatePermissions(context)
			return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		} else if (TrackerServiceApi.isActive(context)) {
			// Stop evaluation is unchanged: only reconsider continuation at the full confidence
			// threshold so a borderline reading never tears down an active session.
			if (activity.confidence >= REQUIRED_CONFIDENCE) {
				val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value ?: run {
					cancelAutomaticStopGrace()
					return ActivityAutomationDeliveryResult.ACCEPTED
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
			return ActivityAutomationDeliveryResult.ACCEPTED
		} else {
			cancelAutomaticStopGrace()
			// The full-confidence path is deliberately independent of optional Steps. Consult the
			// shared, durably admitted Steps snapshot only when it can widen a lower-confidence
			// ON_FOOT decision.
			val hasRecentSteps = shouldConsultStepCorroboration(
				activity.type.groupedActivity,
				activity.confidence,
				REQUIRED_CONFIDENCE,
				STEP_CORROBORATED_CONFIDENCE,
			) &&
				getEntryPoint(context).sharedStepSourceController().hasRecentControlSteps()
			if (
				isOnFootAutoStartCorroborated(
					groupedActivity = activity.type.groupedActivity,
					confidence = activity.confidence,
					requiredConfidence = REQUIRED_CONFIDENCE,
					corroboratedConfidence = STEP_CORROBORATED_CONFIDENCE,
					hasRecentSteps = hasRecentSteps,
				) &&
				canBackgroundTrack(context, activity.type.groupedActivity) &&
				canTrackerServiceBeStarted(context)
			) {
				durableActivityStartContextDisposition(startContext)?.let { return it }
				val trigger = automaticTrigger
					?: return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
				return requestAutomaticStart(trigger)
			}
			return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		}
	}

	private suspend fun handleTransitionUpdate(
		context: Context,
		activity: ActivityTransitionData,
		startContext: ActivityAutomationStartContext,
		automaticTrigger: AutomaticTrackingStartTrigger?,
		requestAutomaticStart: suspend (AutomaticTrackingStartTrigger) -> ActivityAutomationDeliveryResult,
	): ActivityAutomationDeliveryResult {
		if (!context.hasActivityPermission) {
			cancelAutomaticStopGrace()
			revalidatePermissions(context)
			return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		} else if (TrackerServiceApi.isActive(context)) {
			val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value ?: run {
				cancelAutomaticStopGrace()
				return ActivityAutomationDeliveryResult.ACCEPTED
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
			return ActivityAutomationDeliveryResult.ACCEPTED
		} else {
			cancelAutomaticStopGrace()
			if (canBackgroundTrack(context, activity.activity.groupedActivity) &&
				canTrackerServiceBeStarted(context)
			) {
				durableActivityStartContextDisposition(startContext)?.let { return it }
				val trigger = automaticTrigger
					?: return ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
				return requestAutomaticStart(trigger)
			}
			return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		}
	}

	/** Replays a post-admission activity effect; safe to invoke more than once after a crash. */
	internal suspend fun handleDurableActivityEvidence(
		context: Context,
		evidence: ActivityAutomationDeliveryEnvelope,
		controlConsentEpoch: Long,
		currentAutomationEpoch: Long,
		startContext: ActivityAutomationStartContext,
		requestAutomaticStart: suspend (AutomaticTrackingStartTrigger) -> ActivityAutomationDeliveryResult,
	): ActivityAutomationDeliveryResult {
		val authority = activityAutomationAuthority
		durableActivityAuthorityDisposition(
			paramsInitialized = authority.paramsInitialized,
			activePolicyRevision = authority.activePolicyRevision,
			paramsPolicyRevision = authority.paramsPolicyRevision,
			activityControlEligible = authority.activityControlEligible,
			currentControlConsentEpoch = authority.activityControlConsentEpoch,
			effectControlConsentEpoch = controlConsentEpoch,
			currentAutomationEpoch = currentAutomationEpoch,
			effectAutomationEpoch = evidence.automationEpoch,
		)?.let { return it }
		val rollout = getEntryPoint(context).trackingRolloutStateStore().load()
		val startPlan = activityAutomaticStartPlan(
			context.applicationContext,
			cachedParamsSnapshot(),
			rollout,
		)
		if (startPlan.captureSourceMask == 0L) {
			return ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
		}
		val automaticTrigger = evidence.toAutomaticTrackingStartTrigger(
			startContext = startContext,
			sourcePolicyRevision = requireNotNull(authority.activePolicyRevision),
			intendedCaptureSourceMask = startPlan.captureSourceMask,
			requestedCaptureSourceMask = startPlan.requestedCaptureSourceMask,
			intendedForegroundServiceTypeMask = startPlan.foregroundServiceTypeMask,
		)
		return if (evidence.transitionType == null) {
			handleActivityUpdate(
				context.applicationContext,
				RecognizedActivity(evidence.activityType, evidence.confidence),
				startContext,
				automaticTrigger,
				requestAutomaticStart,
			)
		} else {
			handleTransitionUpdate(
				context.applicationContext,
				ActivityTransitionData(evidence.activityType, evidence.transitionType),
				startContext,
				automaticTrigger,
				requestAutomaticStart,
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
		return !entryPoint.lockManager().isLocked &&
			!context.powerManager.isPowerSaveMode &&
			hasAnythingToTrack(
				params = cachedParamsSnapshot(),
				locationAvailable = context.hasLocationPermission,
				activityAvailable = context.hasActivityPermission,
				stepsAvailable = context.hasActivityPermission && context.hasStepCounterSensor,
				wifiAvailable = context.hasWifiScanPermission,
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

	private fun reinitializeRequest(
		context: Context,
		useTransitionApi: Boolean,
		recoveryCompletion: CompletableDeferred<AutomaticControlRecoveryResult>? = null,
		hadActiveRegistration: Boolean = isActive,
	) {
		val generation = ++requestMutationGeneration
		val monitor = getEntryPoint(context).automaticStartTransitionMonitor()
		val watcherController = getWatcherController(context)
		enqueueRequestMutation {
			var recoveryResult = AutomaticControlRecoveryResult.RETRYABLE
			try {
				val result = monitor.reconcile(
					enabled = true,
					useTransitionApi = useTransitionApi,
					continuousIntervalSeconds = activityFreqSeconds,
					transitions = buildTransitions().toSet(),
				)
				recoveryResult = automaticControlRecoveryResult(result)
				check(recoveryResult == AutomaticControlRecoveryResult.ACCEPTED) {
					"Unable to arm background activity recognition request: ${result.failureCode}"
				}
				if (generation != requestMutationGeneration || !isActive) {
					recoveryResult = if (isActive) {
						AutomaticControlRecoveryResult.RETRYABLE
					} else {
						AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
					}
					return@enqueueRequestMutation
				}

				// Steps is an optional broker consumer. The shared controller joins CONTROL_AUTOSTART
				// only when its explicit policy epoch is eligible.
				reconcileStepAutomaticControl(
					context,
					shouldUseStepCorroboration(useTransitionApi, stepControlEligible),
				)
				recognitionUpdatesJob?.cancel()
				recognitionUpdatesJob = null
				watcherController.poke()
			} catch (exception: CancellationException) {
				recoveryCompletion?.cancel(exception)
				throw exception
			} catch (exception: Exception) {
				if (recoveryResult == AutomaticControlRecoveryResult.ACCEPTED) {
					recoveryResult = AutomaticControlRecoveryResult.RETRYABLE
				}
				val retainExistingRegistration =
					shouldRetainAutomaticControlAfterReinitializeFailure(
						hadActiveRegistration = hadActiveRegistration,
						recoveryResult = recoveryResult,
					)
				if (generation == requestMutationGeneration && isActive && !retainExistingRegistration) {
					isActive = false
					val cleanupGeneration = ++requestMutationGeneration
					recognitionUpdatesJob?.cancel()
					recognitionUpdatesJob = null
					reconcileStepAutomaticControl(context, enabled = false)
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
						val cleanupRecoveryResult = automaticControlDisabledRecoveryResult(cleanup)
						if (cleanupRecoveryResult == AutomaticControlRecoveryResult.RETRYABLE) {
							recoveryResult = cleanupRecoveryResult
						}
						check(cleanupRecoveryResult != AutomaticControlRecoveryResult.RETRYABLE) {
							"Unable to clear automatic activity demand: ${cleanup.failureCode}"
						}
					}
					if (removed) {
						watcherController.poke()
					} else {
						recoveryResult = AutomaticControlRecoveryResult.RETRYABLE
					}
				}
				if (recoveryResult == AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED) {
					return@enqueueRequestMutation
				}
				if (recoveryCompletion == null) {
					// Desired state remains durable. The unique WorkManager owner gives optional
					// automatic control a small retry budget without disturbing an existing provider.
					getEntryPoint(context).automaticControlRecoveryScheduler().enqueue()
				}
				throw exception
			} finally {
				recoveryCompletion?.complete(recoveryResult)
			}
		}
	}

	private fun getWatcherController(context: Context): ActivityWatcherController =
		getEntryPoint(context).activityWatcherController()

	private suspend fun reconcileStepAutomaticControl(context: Context, enabled: Boolean) {
		try {
			getEntryPoint(context).sharedStepSourceController().reconcileAutomaticControl(enabled)
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			// Corroboration only widens lower-confidence ON_FOOT starts. A missing/unavailable Steps
			// provider must not disable Activity recognition or its high-confidence path.
			Tracebox.log.error(exception, "Optional Steps automatic-control reconciliation failed")
		}
	}

	private fun enable(
		context: Context,
		recoveryCompletion: CompletableDeferred<AutomaticControlRecoveryResult>? = null,
	) {
		val hadActiveRegistration = isActive
		isActive = true
		reinitializeRequest(
			context,
			cachedParamsSnapshot().transitionDetectionEnabled,
			recoveryCompletion,
			hadActiveRegistration,
		)
	}

	private fun disable(
		context: Context,
		recoveryCompletion: CompletableDeferred<AutomaticControlRecoveryResult>? = null,
	) {
		isActive = false
		cancelAutomaticStopGrace()
		val generation = ++requestMutationGeneration

		val monitor = getEntryPoint(context).automaticStartTransitionMonitor()
		val watcherController = getWatcherController(context)
		enqueueRequestMutation {
			var recoveryResult = AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
			try {
				if (generation != requestMutationGeneration || isActive) {
					return@enqueueRequestMutation
				}
				recognitionUpdatesJob?.cancel()
				recognitionUpdatesJob = null
				reconcileStepAutomaticControl(context, enabled = false)
				val removed = reconcileActivityRequestRemoval(
					shouldContinue = { generation == requestMutationGeneration && !isActive },
				) {
					val result = monitor.reconcile(
						enabled = false,
						useTransitionApi = false,
						continuousIntervalSeconds = activityFreqSeconds,
						transitions = emptySet(),
					)
					recoveryResult = automaticControlDisabledRecoveryResult(result)
					check(recoveryResult != AutomaticControlRecoveryResult.RETRYABLE) {
						"Unable to clear automatic activity demand: ${result.failureCode}"
					}
				}
				recoveryResult = if (removed || generation != requestMutationGeneration || isActive) {
					AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
				} else {
					AutomaticControlRecoveryResult.RETRYABLE
				}
				if (removed) watcherController.poke()
			} catch (exception: CancellationException) {
				recoveryCompletion?.cancel(exception)
				throw exception
			} catch (exception: Exception) {
				recoveryResult = AutomaticControlRecoveryResult.RETRYABLE
				if (recoveryCompletion == null) {
					getEntryPoint(context).automaticControlRecoveryScheduler().enqueue()
				}
				throw exception
			} finally {
				recoveryCompletion?.complete(recoveryResult)
			}
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

		sourcePolicyJob = entryPoint.sourcePolicyRepository().states
			.onEach { authority ->
				val snapshot = (authority as? SourcePolicyAuthorityState.Active)?.snapshot
				val nextPolicyRevision = snapshot?.revision
				val nextActivityConsentEpoch = snapshot
					?.get(TrackingSourceComponent.ACTIVITY)
					?.controlConsentEpoch
				val authorityChanged = automaticControlAuthorityChanged(
					previousPolicyRevision = activeSourcePolicyRevision,
					previousConsentEpoch = activityControlConsentEpoch,
					nextPolicyRevision = nextPolicyRevision,
					nextConsentEpoch = nextActivityConsentEpoch,
				)
				activeSourcePolicyRevision = nextPolicyRevision
				activityControlConsentEpoch = nextActivityConsentEpoch
				reconcileControlEligibility(
					activityEligible = nextActivityConsentEpoch != null,
					stepEligible = snapshot
						?.get(TrackingSourceComponent.STEPS)
						?.controlConsentEpoch != null,
					activityAuthorityChanged = authorityChanged,
				)
				publishActivityAutomationAuthority()
			}
			.retryWhen { error, _ ->
				activeSourcePolicyRevision = null
				activityControlConsentEpoch = null
				reconcileControlEligibility(activityEligible = false, stepEligible = false)
				publishActivityAutomationAuthority()
				Tracebox.log.error(error, "SourcePolicy observation failed")
				delay(SOURCE_POLICY_RETRY_DELAY_MILLIS)
				true
			}
			.launchIn(scope)

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
				paramsInitialized = params.sourcePolicyRevision != null
				publishActivityAutomationAuthority()
			}
			.catch { error ->
				paramsInitialized = false
				publishActivityAutomationAuthority()
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
		).onEach { activityFreqSeconds = it }
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

	/**
	 * Opens automation authority from the still-live Activity callback. The process startup gate and
	 * persisted lock are resolved before policy collectors are allowed to authorize a service start.
	 * The receiver's outer timeout is the lifetime bound; cancellation leaves the durable effect
	 * pending and does not transfer this callback's Android start context to application replay.
	 */
	internal suspend fun initializeAndAwaitActivityAutomationAuthority(context: Context) {
		val ctx = context.applicationContext
		val dependencies = getEntryPoint(ctx)
		dependencies.trackingStartupGate().awaitReady()
		dependencies.lockManager().initializeFromPersistence(ctx)
		val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
		withContext(mainImmediate) { initialize(ctx) }
		activityAutomationAuthorityReady.first { it }
	}

	/**
	 * Recreates the app-scoped automatic-control demand after a destructive data generation change.
	 * Initialization is intentionally idempotent, while this reconciliation is intentionally not a
	 * no-op when [isActive] survived the database clear. Call only after the reopened startup
	 * generation is Ready. Deliberate containment and permission-ineligible optional control are
	 * terminal; transient storage/startup/provider failures remain WorkManager retry work.
	 */
	suspend fun reconcileAutomaticControlDemandAfterStartup(
		context: Context,
	): AutomaticControlRecoveryResult {
		initializeAndAwaitActivityAutomationAuthority(context)
		val ctx = context.applicationContext
		val mainImmediate = (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main
		val recoveryCompletion = CompletableDeferred<AutomaticControlRecoveryResult>()
		withContext(mainImmediate) {
			handleTrackingActivityPreferenceChange(
				cachedParamsSnapshot().autoTrackingMode,
				recoveryCompletion,
			)
		}
		return recoveryCompletion.await()
	}

	private fun handleTrackingActivityPreferenceChange(
		value: Int,
		recoveryCompletion: CompletableDeferred<AutomaticControlRecoveryResult>? = null,
	) {
		val context = appContext ?: return
		// A preference change changes the continuation predicate, so an old incompatible reading
		// must never finish a grace timer under a different policy.
		cancelAutomaticStopGrace()
		val effectiveValue = effectiveAutomaticControlMode(value, activityControlEligible)
		if (recoveryCompletion != null) {
			when {
				effectiveValue == GroupedActivity.STILL.ordinal || !context.hasActivityPermission ->
					disable(context, recoveryCompletion)
				!isActive -> enable(context, recoveryCompletion)
				else -> reinitializeRequest(
					context,
					cachedParamsSnapshot().transitionDetectionEnabled,
					recoveryCompletion,
				)
			}
		} else {
			when (resolveAutoTrackingPreferenceAction(effectiveValue, isActive, context.hasActivityPermission)) {
				AutoTrackingPreferenceAction.DISABLE -> disable(context)
				AutoTrackingPreferenceAction.ENABLE -> enable(context)
				AutoTrackingPreferenceAction.REINITIALIZE ->
					reinitializeRequest(context, cachedParamsSnapshot().transitionDetectionEnabled)
				AutoTrackingPreferenceAction.NONE -> Unit
			}
		}
		if (effectiveValue == GroupedActivity.STILL.ordinal && TrackerServiceApi.isActive(context)) {
			val sessionInfo = TrackerServiceApi.sessionInfoFlow(context).value
			if (shouldStopSessionWhenAutoTrackingDisabled(effectiveValue, sessionInfo?.isInitiatedByUser)) {
				TrackerServiceApi.stopService(context, TrackingStopCandidateReason.EXPLICIT_REQUEST)
			}
		}
	}

	private fun reconcileControlEligibility(
		activityEligible: Boolean,
		stepEligible: Boolean,
		activityAuthorityChanged: Boolean = false,
	) {
		val previousActivityEligibility = activityControlEligible
		val previousStepEligibility = stepControlEligible
		activityControlEligible = activityEligible
		stepControlEligible = stepEligible
		val context = appContext ?: return
		when {
			!activityEligible && isActive ->
				handleTrackingActivityPreferenceChange(cachedParamsSnapshot().autoTrackingMode)
			activityEligible && !previousActivityEligibility && paramsInitialized ->
				handleTrackingActivityPreferenceChange(cachedParamsSnapshot().autoTrackingMode)
			activityEligible && previousActivityEligibility && activityAuthorityChanged &&
				paramsInitialized ->
				handleTrackingActivityPreferenceChange(cachedParamsSnapshot().autoTrackingMode)
			isActive && previousStepEligibility != stepEligible &&
				!cachedParamsSnapshot().transitionDetectionEnabled ->
				reinitializeRequest(context, useTransitionApi = false)
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
				autoTrackingMode = effectiveAutomaticControlMode(
					cachedParamsSnapshot().autoTrackingMode,
					activityControlEligible,
				),
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
		sourcePolicyJob?.cancel()
		sourcePolicyJob = null
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
		activeSourcePolicyRevision = null
		activityControlEligible = false
		activityControlConsentEpoch = null
		stepControlEligible = false
		publishActivityAutomationAuthority()
	}

	private fun publishActivityAutomationAuthority() {
		val paramsPolicyRevision = cachedParamsSnapshot().sourcePolicyRevision
		val snapshot = ActivityAutomationAuthoritySnapshot(
			paramsInitialized = paramsInitialized,
			activePolicyRevision = activeSourcePolicyRevision,
			paramsPolicyRevision = paramsPolicyRevision,
			activityControlEligible = activityControlEligible,
			activityControlConsentEpoch = activityControlConsentEpoch,
		)
		activityAutomationAuthority = snapshot
		_activityAutomationAuthorityReady.value = snapshot.isCoherent
	}
}

private data class ActivityAutomationAuthoritySnapshot(
	val paramsInitialized: Boolean = false,
	val activePolicyRevision: Long? = null,
	val paramsPolicyRevision: Long? = null,
	val activityControlEligible: Boolean = false,
	val activityControlConsentEpoch: Long? = null,
) {
	val isCoherent: Boolean
		get() = isActivityAutomationAuthorityCoherent(
			paramsInitialized = paramsInitialized,
			activePolicyRevision = activePolicyRevision,
			paramsPolicyRevision = paramsPolicyRevision,
		)
}

internal enum class ActivityAutomationDeliveryResult {
	ACCEPTED,
	/** A matching immutable lifecycle intent, not merely an Android enqueue, is durable. */
	LIFECYCLE_INTENT_ACCEPTED,
	/** Android start was attempted once; keep the source outbox pending for lifecycle acceptance. */
	START_REQUESTED,
	RETRY,
	TERMINALLY_SUPPRESSED,
	START_CONTEXT_EXPIRED,
}

internal enum class ActivityAutomationStartContext {
	/** The exact admitted transition is still executing inside its provider callback. */
	FRESH_TRANSITION_CALLBACK,
	/** Durable replay has no Android background foreground-service start exemption. */
	DURABLE_REPLAY,
}

/** Full admitted envelope retained through validation and consumer delivery for auditability. */
internal data class ActivityAutomationDeliveryEnvelope(
	val admissionOrdinal: Long,
	val activityType: DetectedActivityType,
	val confidence: Int,
	val transitionType: ActivityTransitionType?,
	val clockDomainId: String,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
	val registrationGeneration: Long,
	val authorizationRevision: Long,
	val authorizationFingerprint: String,
	val collectedDataEpoch: Long,
	val automationEpoch: Long,
) {
	init {
		require(admissionOrdinal > 0L)
		require(automationEpoch > 0L)
	}
}

internal fun ActivityAutomationDeliveryEnvelope.toAutomaticTrackingStartTrigger(
	startContext: ActivityAutomationStartContext,
	sourcePolicyRevision: Long,
	intendedCaptureSourceMask: Long,
	requestedCaptureSourceMask: Long,
	intendedForegroundServiceTypeMask: Long,
): AutomaticTrackingStartTrigger? {
	val transition = transitionType
	if (startContext != ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK || transition == null) {
		return null
	}
	return AutomaticTrackingStartTrigger(
		triggerId = "activity-transition:$clockDomainId:$admissionOrdinal",
		kind = "ACTIVITY_TRANSITION:${activityType.name}:${transition.name}",
		bootId = clockDomainId,
		observedElapsedRealtimeNanos = observedElapsedRealtimeNanos,
		receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
		expiresElapsedRealtimeNanos = minOf(
			observedElapsedRealtimeNanos.saturatedAdd(MAX_AUTOMATION_EVIDENCE_AGE_NANOS),
			receivedElapsedRealtimeNanos.saturatedAdd(AUTOMATIC_TRIGGER_VALIDITY_NANOS),
		),
		automationEpoch = automationEpoch,
		startContext = AutomaticTrackingStartContext.ACTIVITY_TRANSITION_CALLBACK,
		sourcePolicyRevision = sourcePolicyRevision,
		intendedCaptureSourceMask = intendedCaptureSourceMask,
		requestedCaptureSourceMask = requestedCaptureSourceMask,
		intendedForegroundServiceTypeMask = intendedForegroundServiceTypeMask,
		collectedDataEpoch = collectedDataEpoch,
	)
}

private fun Long.saturatedAdd(increment: Long): Long =
	if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

private const val AUTOMATIC_TRIGGER_VALIDITY_NANOS = 60L * 1_000_000_000L
private const val MAX_AUTOMATION_EVIDENCE_AGE_NANOS = 60L * 1_000_000_000L

internal fun durableActivityStartOutcome(startAccepted: Boolean): ActivityAutomationDeliveryResult =
	if (startAccepted) {
		ActivityAutomationDeliveryResult.START_REQUESTED
	} else {
		ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
	}

internal data class ActivityAutomaticStartPlan(
	val requestedCaptureSourceMask: Long,
	val captureSourceMask: Long,
	val foregroundServiceTypeMask: Long,
)

/** Compact policy-derived envelope persisted before an automatic Android service request. */
internal fun activityAutomaticStartPlan(
	context: Context,
	params: TrackingParamsState,
	rollout: TrackingRolloutState,
): ActivityAutomaticStartPlan {
	val requested = configuredForegroundSources(params)
	val accepted = acceptedForegroundSources(
		requestedSources = requested.filterTo(linkedSetOf()) { source ->
			rollout.isCaptureReachable(source, CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE)
		},
		capabilities = ForegroundSourceCapabilities(
			sdkInt = Build.VERSION.SDK_INT,
			startOrigin = SessionStartOrigin.AUTOMATIC_BACKGROUND_START,
			hasForegroundLocationPermission = context.hasLocationPermission,
			hasBackgroundLocationPermission = context.hasBackgroundLocationPermission,
			locationHardwareAvailable =
				context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION),
			activity = context.hasActivityPermission && Assist.isPlayServicesAvailable(context),
			steps = context.hasActivityPermission && context.hasStepCounterSensor,
			pressure = context.hasPressureSensor,
			wifi = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI) &&
				context.hasWifiScanPermission,
			cell = (
				context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
					(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
						context.packageManager.hasSystemFeature(
							PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS,
						))
				) && context.hasCellScanPermission,
		),
	)
	val captureMask = accepted.fold(0L) { mask, source ->
		mask or (1L shl (source.stableCode - 1))
	}
	val requestedMask = requested.fold(0L) { mask, source ->
		mask or (1L shl (source.stableCode - 1))
	}
	val serviceTypeMask = foregroundServiceTypeCandidates(Build.VERSION.SDK_INT, accepted)
		.singleOrNull()
		?.toLong()
		?: 0L
	return ActivityAutomaticStartPlan(requestedMask, captureMask, serviceTypeMask)
}

internal fun durableActivityStartContextDisposition(
	startContext: ActivityAutomationStartContext,
): ActivityAutomationDeliveryResult? = if (
	startContext == ActivityAutomationStartContext.FRESH_TRANSITION_CALLBACK
) {
	null
} else {
	ActivityAutomationDeliveryResult.START_CONTEXT_EXPIRED
}

internal fun durableActivityAuthorityDisposition(
	paramsInitialized: Boolean,
	activePolicyRevision: Long?,
	paramsPolicyRevision: Long?,
	activityControlEligible: Boolean,
	currentControlConsentEpoch: Long? = null,
	effectControlConsentEpoch: Long? = currentControlConsentEpoch,
	currentAutomationEpoch: Long?,
	effectAutomationEpoch: Long?,
): ActivityAutomationDeliveryResult? = when {
	!isActivityAutomationAuthorityCoherent(
		paramsInitialized,
		activePolicyRevision,
		paramsPolicyRevision,
	) ->
		ActivityAutomationDeliveryResult.RETRY
	!activityControlEligible || currentControlConsentEpoch == null ||
		currentControlConsentEpoch != effectControlConsentEpoch ||
		currentAutomationEpoch != effectAutomationEpoch ->
		ActivityAutomationDeliveryResult.TERMINALLY_SUPPRESSED
	else -> null
}

internal fun isActivityAutomationAuthorityCoherent(
	paramsInitialized: Boolean,
	activePolicyRevision: Long?,
	paramsPolicyRevision: Long?,
): Boolean = paramsInitialized &&
	activePolicyRevision != null &&
	paramsPolicyRevision == activePolicyRevision

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

/** Action to take when the auto-tracking activity requirement preference changes. */
internal enum class AutoTrackingPreferenceAction { NONE, ENABLE, DISABLE, REINITIALIZE }

internal fun effectiveAutomaticControlMode(configuredMode: Int, controlEligible: Boolean): Int =
	configuredMode.takeIf { controlEligible } ?: GroupedActivity.STILL.ordinal

internal fun automaticControlAuthorityChanged(
	previousPolicyRevision: Long?,
	previousConsentEpoch: Long?,
	nextPolicyRevision: Long?,
	nextConsentEpoch: Long?,
): Boolean = previousPolicyRevision != nextPolicyRevision || previousConsentEpoch != nextConsentEpoch

internal fun shouldUseStepCorroboration(
	useTransitionApi: Boolean,
	stepControlEligible: Boolean,
): Boolean = !useTransitionApi && stepControlEligible

internal fun automaticControlRecoveryResult(
	result: ActivityRegistrationResult,
): AutomaticControlRecoveryResult = when {
	result.retryable -> AutomaticControlRecoveryResult.RETRYABLE
	result.status == ActivityRegistrationStatus.APPLIED ||
		result.status == ActivityRegistrationStatus.DEGRADED ->
		AutomaticControlRecoveryResult.ACCEPTED
	result.failureCode in TERMINAL_OPTIONAL_CONTROL_FAILURES ->
		AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
	else -> AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
}

internal fun shouldRetainAutomaticControlAfterReinitializeFailure(
	hadActiveRegistration: Boolean,
	recoveryResult: AutomaticControlRecoveryResult,
): Boolean = hadActiveRegistration && recoveryResult == AutomaticControlRecoveryResult.RETRYABLE

internal fun automaticControlDisabledRecoveryResult(
	result: ActivityRegistrationResult,
): AutomaticControlRecoveryResult = if (result.retryable) {
	AutomaticControlRecoveryResult.RETRYABLE
} else {
	AutomaticControlRecoveryResult.TERMINAL_DISABLED_OR_CONTAINED
}

private val TERMINAL_OPTIONAL_CONTROL_FAILURES = setOf(
	ActivityRegistrationFailureCode.PERMISSION_MISSING,
	ActivityRegistrationFailureCode.MISSING_DURABLE_DEMAND,
)

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
		isStill -> AutoTrackingPreferenceAction.NONE
		!isActive && hasActivityPermission -> AutoTrackingPreferenceAction.ENABLE
		isActive -> AutoTrackingPreferenceAction.REINITIALIZE
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
