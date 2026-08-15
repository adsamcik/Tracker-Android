package com.adsamcik.tracker.tracker.service

import dev.tracebox.Tracebox
import dev.tracebox.api.public
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
import com.adsamcik.tracker.tracker.api.TrackerForegroundServiceRequirements
import com.adsamcik.tracker.tracker.api.TrackerForegroundServiceRequirementsProvider
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.notification.TrackerNotificationChannels
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.receiver.TrackerRestartReceiver
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.LogicalTrackingLifecycleState
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidate
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.resilience.shouldScheduleTrackerRestart
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.coordinator.PlanResolutionContext
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionPlanInputs
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionReconfigureOutcome
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStartOutcome
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStartRequest
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopOutcome
import com.adsamcik.tracker.tracker.source.coordinator.SourceOwner
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSessionOwnership
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.LocationCollectionStrategy
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameConsumer
import com.adsamcik.tracker.tracker.source.projection.EventTrackingFrameOutboxDispatcher
import com.adsamcik.tracker.tracker.worker.HistoricalTrajectoryReconstructionWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import androidx.core.content.ContextCompat
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android lifecycle shell for the tracking service.
 *
 * Owns Android-specific concerns (foreground notification, wake lock,
 * shortcuts, intent parsing) and delegates all business logic to
 * [TrackingOrchestrator].
 */
@AndroidEntryPoint
internal class TrackerService : CoreService() {
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock

	@Inject
	lateinit var controller: TrackerServiceController

	@Inject
	lateinit var lockManager: LockManager

	@Inject
	lateinit var signalProcessors: Set<@JvmSuppressWildcards SignalProcessor>

	@Inject
	lateinit var domainEventRepository: DomainEventRepository

	@Inject
	lateinit var trackingParamsRepository: TrackingParamsRepository

	@Inject
	lateinit var appDatabase: AppDatabase

	@Inject
	lateinit var dispatchers: DispatchersProvider

	@Inject
	lateinit var activityWatcherController: ActivityWatcherServiceController

	@Inject
	lateinit var batteryAwarePolicy: BatteryAwarePolicy

	@Inject
	lateinit var metricDirtyTracker: com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker

	@Inject
	lateinit var activeTrackingSessionStore: ActiveTrackingSessionStore

	@Inject
	lateinit var sourcePipelineRecovery: SourcePipelineRecovery

	@Inject
	lateinit var sourceSession: TrackerServiceSourceSession

	@Inject
	lateinit var collectionMotionController: CollectionMotionController

	@Inject
	lateinit var trackingRolloutStateStore: RoomTrackingRolloutStateStore

	@Inject
	lateinit var bootClockDomainProvider: BootClockDomainProvider

	@Inject
	lateinit var coordinatorTelemetry: TrackingCoordinatorTelemetry

	@Inject
	lateinit var trackingFrameEffects: EventTrackingFrameOutboxDispatcher

	@Inject
	lateinit var foregroundRequirementsProvider: TrackerForegroundServiceRequirementsProvider

	private lateinit var orchestrator: TrackingOrchestrator

	private var lockObservationJob: Job? = null
	private var initializationJob: Job? = null
	private var batteryObservationJob: Job? = null
	private var collectionMotionObservationJob: Job? = null
	private var sessionRecoveryJob: Job? = null
	private var descriptorObservationJob: Job? = null
	private lateinit var cycleDispatcher: TrackingCycleDispatcher
	private var cycleDispatcherScope: CoroutineScope? = null
	private var serviceGeneration: Long = 0L

	// Kept here for intent recovery and power-save check
	private var sessionInfo: TrackerSessionInfo? = null
	private var activeSessionDescriptor: ActiveTrackingSessionDescriptor? = null
	private var gracefulStopRequested = false
	private var stopReason: TrackingStopCandidateReason = TrackingStopCandidateReason.UNKNOWN
	private var coordinatorMetricBaseline: com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics? = null
	private var sessionRolloutState: TrackingRolloutState? = null
	private val restartScheduled = AtomicBoolean(false)
	private var foregroundStarted = false
	private var activeForegroundServiceType: Int? = null
	private var activeForegroundRequirements: TrackerForegroundServiceRequirements? = null
	private val trackingFrameOwnerToken: String
		get() = "tracker-service:$serviceGeneration"

	override fun onCreate() {
		super.onCreate()
		synchronized(SERVICE_GENERATION_LOCK) {
			serviceGeneration = SERVICE_GENERATION_COUNTER.incrementAndGet()
			activeServiceGeneration = serviceGeneration
		}

		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(
			PowerManager.PARTIAL_WAKE_LOCK,
			"signals:TrackerWakeLock"
		)

		orchestrator = TrackingOrchestrator(
			controller = controller,
			signalProcessors = signalProcessors,
			domainEventRepository = domainEventRepository,
			dispatchers = dispatchers,
			appDatabase = appDatabase,
			trackingParamsRepository = trackingParamsRepository,
			runtimeTierAdjuster = { requestedTier ->
				val preserveRequestedFidelity = sessionInfo?.isInitiatedByUser == true ||
					com.adsamcik.tracker.tracker.api.BackgroundTrackingApi.cachedParams.preset ==
					TrackingPreset.HIGH_ACCURACY
				if (preserveRequestedFidelity) requestedTier else batteryAwarePolicy.adjustForBattery(requestedTier)
			},
			onDailySummaryWritten = {
				metricDirtyTracker.markDirty(
					com.adsamcik.tracker.stats.api.metric.MetricKeys.TABLE_DAILY_SUMMARY
				)
			},
			onSourcePlanInputsChanged = { settings, demands ->
				val rollout = sessionRolloutState
				val foregroundReady = rollout == null || prepareForegroundForSourcePlan(settings, rollout)
				val reconfigured = if (foregroundReady) {
					sourceSession.reconfigure(sourcePlanInputs(settings, demands))
				} else {
					null
				}
				if (reconfigured == null || reconfigured is SourceSessionReconfigureOutcome.Rejected) {
					requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
				}
			},
		)
		createCycleDispatcher()

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.updateShortcut(
				this,
				ShortcutData(
					Shortcuts.TRACKING_ID,
					R.string.shortcut_stop_tracking,
					R.string.shortcut_stop_tracking_long,
					com.adsamcik.tracker.shared.base.R.drawable.ic_pause_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.STOP_COLLECTION
				)
			)
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)

		if (intent?.action == TrackerServiceContract.ACTION_GRACEFUL_STOP) {
			requestGracefulStop(startId, intent.stopCandidateReason())
			return START_NOT_STICKY
		}

		val promotionIsUserInitiated = intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
			?: sessionInfo?.isInitiatedByUser
			?: DEFAULT_IS_USER_INITIATED
		val promotionIsAmbient = intent?.getBooleanExtra(ARG_IS_AMBIENT, false)
			?: activeSessionDescriptor?.isAmbient
			?: false
		val promotionRequirements = intent?.foregroundRequirements()
			?: foregroundRequirementsProvider.current(
				promotionIsUserInitiated,
				promotionIsAmbient,
			)
		if (promotionRequirements == null) {
			onForegroundStartFailed(stopService = true)
			return START_NOT_STICKY
		}
		if (
			!ensureForegroundStarted(
				requirements = promotionRequirements,
				requiresBackgroundLocation = !promotionIsUserInitiated && !promotionIsAmbient,
			)
		) {
			return START_NOT_STICKY
		}

		val recoveredSessionInfo = sessionInfo ?: controller.sessionInfoFlow.value
		val isWatchdogRestart = intent?.hasExtra(ARG_POLICY_TIER) == true
		when (
			trackerServiceStartRecoveryDisposition(
				startFlags = flags,
				hasInMemorySession = recoveredSessionInfo != null,
				gracefulStopRequested = gracefulStopRequested,
				isWatchdogStart = isWatchdogRestart,
			)
		) {
			TrackerServiceStartRecoveryDisposition.RESOLVE_DURABLE_START -> {
				val requestedDescriptor = intent?.toNewStartDescriptor()
				sessionRecoveryJob?.cancel()
				sessionRecoveryJob = launch {
					val resolution = resolveTrackerServiceStartRequest(
						storeResult = activeTrackingSessionStore.read(),
						requestedDescriptor = requestedDescriptor,
					)
					if (gracefulStopRequested) return@launch
					when (resolution) {
						is TrackerServiceStartRequestResolution.Begin -> {
							beginSession(
								resolution.descriptor,
								startId,
								isRecovery = resolution.isRecovery,
							)
						}
						is TrackerServiceStartRequestResolution.StoreFailure -> {
							Tracebox.log.error(
								resolution.cause,
								TrackerTraceboxTemplates.TRACKING_SESSION_RECOVERY_FAILED,
							)
							requestGracefulStop(startId)
						}
						TrackerServiceStartRequestResolution.DoNotStart ->
							requestGracefulStop(startId)
					}
				}
				return if (
					intent == null ||
					flags and Service.START_FLAG_REDELIVERY != 0 ||
					requestedDescriptor?.isUserInitiated == true
				) {
					START_REDELIVER_INTENT
				} else {
					START_NOT_STICKY
				}
			}
			TrackerServiceStartRecoveryDisposition.IGNORE_DUPLICATE_REDELIVERY ->
				return if (recoveredSessionInfo?.isInitiatedByUser == true) {
					START_REDELIVER_INTENT
				} else {
					START_NOT_STICKY
				}
			TrackerServiceStartRecoveryDisposition.IGNORE_AFTER_GRACEFUL_STOP ->
				return START_NOT_STICKY
			TrackerServiceStartRecoveryDisposition.HANDLE_START_INTENT -> Unit
		}
		val watchdogDescriptor = intent?.takeIf { isWatchdogRestart }?.toRestartDescriptor()
		if (isWatchdogRestart && watchdogDescriptor?.isRestartEligible != true) {
			requestGracefulStop(startId)
			return START_NOT_STICKY
		}
		if (isWatchdogRestart && sessionInfo != null && !gracefulStopRequested) {
			return if (sessionInfo?.isInitiatedByUser == true) {
				START_REDELIVER_INTENT
			} else {
				START_NOT_STICKY
			}
		}
		val isUserInitiated = watchdogDescriptor?.isUserInitiated
			?: intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
			?: recoveredSessionInfo?.isInitiatedByUser
			?: DEFAULT_IS_USER_INITIATED
		val isAmbient = watchdogDescriptor?.isAmbient
			?: intent?.getBooleanExtra(ARG_IS_AMBIENT, false)
			?: !isUserInitiated
		val recoveredTier = watchdogDescriptor?.policyTier ?: if (intent == null) {
			controller.policyTierFlow.value.takeUnless { it == PolicyTier.OFF }
		} else {
			intent.getStringExtra(ARG_POLICY_TIER)
				?.let { name -> PolicyTier.entries.firstOrNull { it.name == name } }
		}
		beginSession(
			watchdogDescriptor ?: ActiveTrackingSessionDescriptor(
				isUserInitiated = isUserInitiated,
				isAmbient = isAmbient,
				policyTier = recoveredTier ?: PolicyTier.OFF,
			),
			startId,
			isRecovery = isWatchdogRestart || intent == null,
		)

		return if (isUserInitiated) START_REDELIVER_INTENT else START_NOT_STICKY
	}

	/** Parses only a watchdog/restart intent; ordinary starts deliberately create a new logical ID. */
	private fun Intent.toRestartDescriptor(): ActiveTrackingSessionDescriptor? {
		val tier = getStringExtra(ARG_POLICY_TIER)
			?.let { name -> PolicyTier.entries.firstOrNull { it.name == name } }
			?: return null
		if (tier == PolicyTier.OFF) return null

		val lifecycleStateValue = getStringExtra(TrackerServiceContract.ARG_LIFECYCLE_STATE)
		val lifecycleState = when {
			lifecycleStateValue == null -> LogicalTrackingLifecycleState.ACTIVE
			else -> LogicalTrackingLifecycleState.entries.firstOrNull {
				it.name == lifecycleStateValue
			} ?: LogicalTrackingLifecycleState.STOP_CANDIDATE
		}
		val stopCandidate = if (lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE) {
			TrackingStopCandidate(
				reason = getStringExtra(TrackerServiceContract.ARG_STOP_CANDIDATE_REASON)
					?.let { name ->
						TrackingStopCandidateReason.entries.firstOrNull { it.name == name }
					}
					?: TrackingStopCandidateReason.UNKNOWN,
				requestedAtEpochMs = getLongExtra(
					TrackerServiceContract.ARG_STOP_CANDIDATE_REQUESTED_AT_EPOCH_MS,
					0L,
				).takeIf { it > 0L },
			)
		} else {
			null
		}

		return ActiveTrackingSessionDescriptor(
			isUserInitiated = getBooleanExtra(ARG_IS_USER_INITIATED, false),
			isAmbient = getBooleanExtra(ARG_IS_AMBIENT, false),
			policyTier = tier,
			logicalTrackingId = getStringExtra(TrackerServiceContract.ARG_LOGICAL_TRACKING_ID)
				?.takeIf { it.isNotBlank() }
				?: java.util.UUID.randomUUID().toString(),
			lifecycleState = lifecycleState,
			lifecycleRevision = getLongExtra(
				TrackerServiceContract.ARG_LIFECYCLE_REVISION,
				0L,
			).coerceAtLeast(0L),
			lifecycleChangedAtEpochMs = getLongExtra(
				TrackerServiceContract.ARG_LIFECYCLE_CHANGED_AT_EPOCH_MS,
				0L,
			).takeIf { it > 0L },
			stopCandidate = stopCandidate,
		)
	}

	private fun Intent.toNewStartDescriptor(): ActiveTrackingSessionDescriptor {
		val isUserInitiated = getBooleanExtra(ARG_IS_USER_INITIATED, false)
		return ActiveTrackingSessionDescriptor(
			isUserInitiated = isUserInitiated,
			isAmbient = getBooleanExtra(ARG_IS_AMBIENT, false),
			policyTier = getStringExtra(ARG_POLICY_TIER)
				?.let { name -> PolicyTier.entries.firstOrNull { it.name == name } }
				?: PolicyTier.OFF,
		)
	}

	private fun Intent.foregroundRequirements(): TrackerForegroundServiceRequirements? {
		if (
			!hasExtra(TrackerServiceContract.ARG_REQUIRES_LOCATION) &&
			!hasExtra(TrackerServiceContract.ARG_REQUIRES_HEALTH) &&
			!hasExtra(TrackerServiceContract.ARG_HAS_SIGNAL_SOURCES)
		) {
			return null
		}
		val requiresLocation = getBooleanExtra(
			TrackerServiceContract.ARG_REQUIRES_LOCATION,
			false,
		)
		val requiresHealth = getBooleanExtra(
			TrackerServiceContract.ARG_REQUIRES_HEALTH,
			false,
		)
		val hasSignalSources = getBooleanExtra(
			TrackerServiceContract.ARG_HAS_SIGNAL_SOURCES,
			false,
		)
		if (!requiresLocation && !requiresHealth && !hasSignalSources) return null
		return TrackerForegroundServiceRequirements(
			requiresLocation = requiresLocation,
			requiresHealth = requiresHealth,
			hasSignalSources = hasSignalSources,
		)
	}

	private fun Intent.stopCandidateReason(): TrackingStopCandidateReason =
		getStringExtra(TrackerServiceContract.ARG_STOP_CANDIDATE_REASON)
			?.let { name -> TrackingStopCandidateReason.entries.firstOrNull { it.name == name } }
			?: TrackingStopCandidateReason.EXPLICIT_REQUEST

	private fun beginSession(
		startDescriptor: ActiveTrackingSessionDescriptor,
		startId: Int,
		isRecovery: Boolean = false,
	) {
		Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_START_REQUESTED)
		gracefulStopRequested = false
		stopReason = TrackingStopCandidateReason.UNKNOWN
		coordinatorMetricBaseline = coordinatorTelemetry.snapshot()
		restartScheduled.set(false)
		// A restart retains the logical session identity but is a distinct Android-service run.
		val serviceRunDescriptor = startDescriptor.forNewServiceRun(System.currentTimeMillis())
		val isUserInitiated = serviceRunDescriptor.isUserInitiated
		val isAmbient = serviceRunDescriptor.isAmbient
		val provisionalDescriptor = serviceRunDescriptor.copy(
			policyTier = resolveInitialPolicyTier(
				isUserInitiated = isUserInitiated,
				isAmbient = isAmbient,
				locationEnabled = false,
				recoveredTier = startDescriptor.policyTier.takeUnless { it == PolicyTier.OFF },
			),
		)
		activeSessionDescriptor = provisionalDescriptor

		controller.updateServiceRunning(true)

		this.sessionInfo = TrackerSessionInfo(isUserInitiated)
		controller.updateSessionInfo(this.sessionInfo)

		// A user-initiated restart must also remove the automatic-session observer;
		// otherwise a later lock emission can stop the replacement user session.
		lockObservationJob?.cancel()
		lockObservationJob = null
		if (!isUserInitiated) {
			lockObservationJob = launch {
				lockManager.isLockedFlow.collect { isLocked ->
					if (isLocked) {
						requestGracefulStop(reason = TrackingStopCandidateReason.DEVICE_LOCKED)
					}
				}
			}
		}

		activityWatcherController.poke(trackerRunning = true)

		// Re-entrancy guard: a second onStartCommand arriving while the first
		// initialization coroutine is still suspended would race the orchestrator's
		// component teardown/setup. Cancel the previous init before starting a new
		// one so only one initialization is in flight at a time.
		val previousInitialization = initializationJob
		val previousServiceTeardown = synchronized(SERVICE_GENERATION_LOCK) {
			lastTeardownGate
		}
		previousInitialization?.cancel()
		initializationJob = launch {
			try {
				if (previousInitialization != null) {
					withTimeout(PREVIOUS_INITIALIZATION_WAIT_TIMEOUT_MILLIS) {
						previousInitialization.cancelAndJoin()
					}
				}
				TRACKING_LIFECYCLE_BARRIER.runAfter(
					previousTeardown = previousServiceTeardown?.job,
					waitTimeoutMillis = PREVIOUS_TEARDOWN_WAIT_TIMEOUT_MILLIS,
				) {
					recoverIncompleteTeardown(previousServiceTeardown)
					saveActiveSession(provisionalDescriptor)
					trackingFrameEffects.detach(trackingFrameOwnerToken)
					if (!quiesceCycleDispatcherForReplacement()) {
						requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
						return@runAfter
					}
					createCycleDispatcher()

					// Resolve semantic source plans before starting the event-owned session runtimes.
					val trackingParams = trackingParamsRepository.data.first()
					val rolloutState = trackingRolloutStateStore.load()
					sessionRolloutState = rolloutState
					val ownership = TrackingSessionOwnership.resolve(rolloutState, trackingParams)
					val foregroundRequirements = resolveForegroundRequirements(
						settings = trackingParams,
						isUserInitiated = isUserInitiated,
						isAmbient = isAmbient,
					)
					if (foregroundRequirements == null) {
						requestGracefulStop(
							startId,
							TrackingStopCandidateReason.CAPTURE_UNAVAILABLE,
						)
						return@runAfter
					}
					val locationEnabled = foregroundRequirements.requiresLocation
					val requestedInitialTier = resolveInitialPolicyTier(
						isUserInitiated = isUserInitiated,
						isAmbient = isAmbient,
						locationEnabled = locationEnabled,
						recoveredTier = startDescriptor.policyTier.takeUnless { it == PolicyTier.OFF },
					)
					val initialTier = if (
						isUserInitiated || trackingParams.preset == TrackingPreset.HIGH_ACCURACY
					) {
						requestedInitialTier
					} else {
						batteryAwarePolicy.adjustForBattery(requestedInitialTier)
					}
					if (!ensureForegroundStarted(
						requirements = foregroundRequirements,
						requiresBackgroundLocation = !isUserInitiated && !isAmbient,
					)) {
						return@runAfter
					}
					var descriptor = provisionalDescriptor.copy(policyTier = initialTier)
					activeSessionDescriptor = descriptor
					if (descriptor != provisionalDescriptor) {
						saveActiveSession(descriptor)
					}
					controller.updatePolicyTier(initialTier)
					withContext(dispatchers.default) {
						orchestrator.initialize(
							context = this@TrackerService,
							isSessionUserInitiated = isUserInitiated,
							initialTier = initialTier,
							scope = this@TrackerService,
							logicalTrackingId = descriptor.logicalTrackingId,
							resumeSessionSegmentId = descriptor.sessionSegmentId,
							rolloutState = rolloutState,
						)
					}
					descriptor = descriptor.copy(
						sessionSegmentId = orchestrator.currentSessionSegmentId(),
					)
					activeSessionDescriptor = descriptor
					saveActiveSession(descriptor)
					observeDescriptorTierChanges(descriptor)
					trackingFrameEffects.attach(
						trackingFrameOwnerToken,
						descriptor.logicalTrackingId,
						EventTrackingFrameConsumer { cycle ->
							val completion = cycleDispatcher.enqueue(cycle)
							completion.join()
							check(!completion.isCancelled) { "Event tracking-frame delivery failed" }
						},
					)
					collectionMotionController.startSession(
						descriptor.logicalTrackingId,
						descriptor.serviceRunId,
						SystemClock.elapsedRealtimeNanos(),
						initialMotion = true,
					)
					when (val sourceStart = sourceSession.start(
						SourceSessionStartRequest(
							rollout = rolloutState,
							ownership = ownership,
							logicalTrackingId = descriptor.logicalTrackingId,
							serviceRunId = descriptor.serviceRunId,
							origin = when {
								isRecovery -> SessionStartOrigin.RESTORE_AFTER_PROCESS_DEATH
								isUserInitiated -> SessionStartOrigin.MANUAL_FOREGROUND
								else -> SessionStartOrigin.AUTOMATIC_ACTIVITY_TRANSITION
							},
							foregroundCapabilityFlags = activeForegroundServiceType?.toLong() ?: 0L,
							planInputs = sourcePlanInputs(trackingParams, orchestrator.currentSourceDemands()),
							ownerToken = "event-source:$serviceGeneration:${descriptor.serviceRunId}",
						),
					)) {
						is SourceSessionStartOutcome.Rejected -> {
							collectionMotionController.stopSession(descriptor.serviceRunId)
							Tracebox.log.error(
								TrackerTraceboxTemplates.TRACKING_SOURCE_SESSION_START_REJECTED,
							)
							requestGracefulStop(reason = TrackingStopCandidateReason.INITIALIZATION_FAILURE)
							return@runAfter
						}
						else -> Unit
					}
					sourcePipelineRecovery.drainCommittedWork()
					observeCollectionMotionPolicy()

					batteryObservationJob?.cancel()
					batteryObservationJob = launch {
						batteryAwarePolicy.batteryLevelUpdates.collect {
							orchestrator.onBatteryLevelChanged(scope = this@TrackerService)
							val currentSettings = trackingParamsRepository.data.first()
							val sourceResult = sourceSession.reconfigure(
								sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
							)
							if (sourceResult == null || sourceResult is SourceSessionReconfigureOutcome.Rejected) {
								requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
							}
						}
					}
					Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_STARTED)

				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (!e.isTrackingOperationalFailure()) throw e
				Tracebox.log.error(
					e,
					TrackerTraceboxTemplates.TRACKING_START_STORAGE_UNAVAILABLE,
				)
				requestGracefulStop(reason = TrackingStopCandidateReason.INITIALIZATION_FAILURE)
			}
		}
	}

	private fun observeDescriptorTierChanges(
		initialDescriptor: ActiveTrackingSessionDescriptor,
	) {
		descriptorObservationJob?.cancel()
		descriptorObservationJob = launch {
			controller.policyTierFlow
				.collect { tier ->
					if (tier == PolicyTier.OFF) return@collect
					val updated = (activeSessionDescriptor ?: initialDescriptor).copy(policyTier = tier)
					activeSessionDescriptor = updated
					saveActiveSession(updated)
				}
		}
	}

	private fun observeCollectionMotionPolicy() {
		collectionMotionObservationJob?.cancel()
		collectionMotionObservationJob = launch {
			launch {
				collectionMotionController.policy
					.map { it.acquisitionProfile }
					.distinctUntilChanged()
					.collect { profile ->
						coordinatorTelemetry.recordMotionPolicyChange(
							stationaryOptimized = profile.stationary,
							fullFidelity = profile.locationStrategy == LocationCollectionStrategy.FULL_FIDELITY,
						)
						val currentSettings = trackingParamsRepository.data.first()
						val result = sourceSession.reconfigure(
							sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
						)
						if (result == null || result is SourceSessionReconfigureOutcome.Rejected) {
							requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
						}
					}
			}
			while (isActive) {
				delay(MOTION_POLICY_TICK_MILLIS)
				collectionMotionController.tick(SystemClock.elapsedRealtimeNanos())
			}
		}
	}

	/**
	 * Starts or updates the service with foreground-service types matching active data access.
	 *
	 * Android supports calling `startForeground` again when the service starts using different
	 * source types. This lets non-GPS sessions stay on health/special-use and promotes to location
	 * before a GPS trigger is enabled. A location-required session never falls back to a non-location
	 * type; doing so would leave GPS collection running without the required foreground declaration.
	 *
	 * @return true when the required foreground declaration is active.
	 */
	@Synchronized
	private fun ensureForegroundStarted(
		requirements: TrackerForegroundServiceRequirements,
		requiresBackgroundLocation: Boolean = false,
		stopServiceOnFailure: Boolean = true,
	): Boolean {
		val permissionCapabilities = trackingPermissionCapabilities()
		if (
			requirements.requiresLocation &&
			(!permissionCapabilities.hasForegroundLocation ||
				(requiresBackgroundLocation && !permissionCapabilities.hasBackgroundLocation))
		) {
			onForegroundStartFailed(stopServiceOnFailure)
			return false
		}

		val candidates = foregroundServiceTypeCandidates(
			sdkInt = Build.VERSION.SDK_INT,
			requirements = requirements,
			hasLocationPermission = permissionCapabilities.hasForegroundLocation,
			hasActivityPermission = hasActivityPermission,
		)
		val preferredType = candidates.firstOrNull()
		if (preferredType == null && candidates.isEmpty()) {
			onForegroundStartFailed(stopServiceOnFailure)
			return false
		}
		if (
			foregroundStarted &&
			activeForegroundServiceType == preferredType &&
			activeForegroundRequirements == requirements
		) {
			return true
		}

		val notification = TrackerNotificationManager.getForegroundNotification(
			context = this,
			usesLocation = requirements.requiresLocation,
			isUserInitiatedSession = sessionInfo?.isInitiatedByUser,
		)
		for (type in candidates) {
			if (tryStartForeground(notification, type)) {
				foregroundStarted = true
				activeForegroundServiceType = type
				activeForegroundRequirements = requirements
				onForegroundServiceTypeChanged()
				return true
			}
		}
		onForegroundStartFailed(stopServiceOnFailure)
		return false
	}

	private fun onForegroundServiceTypeChanged() {
		if (::orchestrator.isInitialized) {
			orchestrator.notificationComponent.onForegroundServiceTypeChanged()
		}
	}

	private fun tryStartForeground(notification: Notification, type: Int?): Boolean = try {
		if (type != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(TrackerNotificationManager.NOTIFICATION_ID, notification, type)
		} else {
			startForeground(TrackerNotificationManager.NOTIFICATION_ID, notification)
		}
		true
	} catch (exception: SecurityException) {
		false
	} catch (@Suppress("TooGenericExceptionCaught") exception: RuntimeException) {
		val isForegroundStartRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
			exception::class.java.name == "android.app.ForegroundServiceStartNotAllowedException"
		if (isForegroundStartRestricted) {
			false
		} else {
			throw exception
		}
	}

	private fun onForegroundStartFailed(stopService: Boolean) {
		if (stopService) {
			Tracebox.log.error(TrackerTraceboxTemplates.TRACKING_START_FAILED)
			TrackerNotificationManager.postStartFailedNotification(this)
			requestGracefulStop(reason = TrackingStopCandidateReason.PERMISSION_UNAVAILABLE)
		}
	}

	private suspend fun processCycleUpdate(cycle: TrackingCycle) {
		val wakeLockStartedAtNanos = Time.elapsedRealtimeNanos
		wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
		try {
			Tracebox.log.performanceSuspend(TrackerTraceboxTemplates.PROCESS_TRACKING_CYCLE) {
				orchestrator.onCycleUpdate(this@TrackerService, cycle)
			}
		} finally {
			if (wakeLock.isHeld) wakeLock.release()
			coordinatorTelemetry.recordTrackingFrame(
				wakeLockNanos = Time.elapsedRealtimeNanos - wakeLockStartedAtNanos,
			)
		}

		// Power-save check (Android concern, kept in service)
		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) {
			requestGracefulStop(reason = TrackingStopCandidateReason.POWER_SAVER)
		}
	}

	private fun createCycleDispatcher() {
		val dispatcherScope = CoroutineScope(SupervisorJob() + dispatchers.default)
		cycleDispatcherScope = dispatcherScope
		cycleDispatcher = TrackingCycleDispatcher(
			scope = dispatcherScope,
			dispatcher = dispatchers.default,
			capacity = TRACKING_CYCLE_QUEUE_CAPACITY,
			processCycle = ::processCycleUpdate,
		)
	}

	private suspend fun quiesceCycleDispatcherForReplacement(): Boolean {
		if (!::cycleDispatcher.isInitialized) return true

		val drained = try {
			withTimeoutOrNull(CYCLE_DRAIN_TIMEOUT_MILLIS) {
				cycleDispatcher.closeAndDrain()
				true
			} == true
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (!e.isTrackingOperationalFailure()) throw e
			false
		}

		val cancelled = try {
			withTimeoutOrNull(CYCLE_CANCEL_TIMEOUT_MILLIS) {
				cycleDispatcher.cancelAndJoin()
				true
			} == true
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (!e.isTrackingOperationalFailure()) throw e
			false
		}
		cycleDispatcherScope?.cancel()
		cycleDispatcherScope = null
		if (!drained || !cancelled) {
			Tracebox.log.warn(TrackerTraceboxTemplates.TRACKING_SHUTDOWN_DEGRADED)
		}
		return cancelled
	}

	private fun requestGracefulStop(
		startId: Int? = null,
		reason: TrackingStopCandidateReason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
	) {
		if (gracefulStopRequested) return
		Tracebox.log.info(
			TrackerTraceboxTemplates.TRACKING_STOP_REQUESTED,
			public(reason),
		)
		gracefulStopRequested = true
		stopReason = reason
		descriptorObservationJob?.cancel()
		descriptorObservationJob = null
		collectionMotionObservationJob?.cancel()
		collectionMotionObservationJob = null
		// Initialization can still be suspended on preferences, foreground setup, or component
		// construction.  Cancel it before persisting the candidate so it cannot later overwrite the
		// terminal descriptor with the provisional ACTIVE record.
		initializationJob?.cancel()
		val stopCandidate = activeSessionDescriptor?.proposeStop(
			reason = reason,
			changedAtEpochMs = System.currentTimeMillis(),
		)
		activeSessionDescriptor = stopCandidate
		launch {
			// Keep the stop candidate durable until teardown completes.  An unexpected kill in this
			// interval must not be interpreted as a user-session crash eligible for restart.
			if (stopCandidate != null) {
				saveActiveSession(stopCandidate)
			}
			if (startId == null) {
				stopSelf()
			} else {
				stopSelfResult(startId)
			}
		}
	}

	private suspend fun clearCompletedStopCandidate() {
		val stopCandidate = activeSessionDescriptor
			?.takeIf { it.lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE }
			?: return
		val result = activeTrackingSessionStore.clearIfCurrent(stopCandidate)
		if (result is ActiveTrackingSessionStoreResult.Failure) {
			Tracebox.log.error(
				result.cause,
				TrackerTraceboxTemplates.TRACKING_SESSION_STORE_FAILED,
			)
			return
		}
		// Do not overwrite a newer service run's descriptor if it won the atomic comparison.
		if ((result as ActiveTrackingSessionStoreResult.Success).descriptor == null) {
			activeSessionDescriptor = null
		}
	}

	private suspend fun saveActiveSession(descriptor: ActiveTrackingSessionDescriptor) {
		val result = activeTrackingSessionStore.save(descriptor)
		if (result is ActiveTrackingSessionStoreResult.Failure) {
			Tracebox.log.error(
				result.cause,
				TrackerTraceboxTemplates.TRACKING_SESSION_STORE_FAILED,
			)
		}
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		scheduleRestartWatchdog()
		super.onTaskRemoved(rootIntent)
	}

	private fun scheduleRestartWatchdog(restartBeforeTeardown: Boolean = false) {
		val descriptor = activeSessionDescriptor
		if (
			!shouldScheduleTrackerRestart(
				descriptor = descriptor,
				gracefulStopRequested = gracefulStopRequested,
				restartAlreadyScheduled = restartScheduled.get(),
			)
		) {
			return
		}
		if (!restartScheduled.compareAndSet(false, true)) return
		if (restartBeforeTeardown && TrackerServiceApi.restartService(this, requireNotNull(descriptor))) {
			return
		}
		sendBroadcast(TrackerRestartReceiver.intent(this, requireNotNull(descriptor)))
	}

	override fun onDestroy() {
		// Generic broadcasts are not Android 12+ FGS-start exemptions. Re-request the
		// service while this process still owns the active FGS, before teardown changes
		// the UID state; retain the receiver fallback for vendor lifecycle variants.
		scheduleRestartWatchdog(restartBeforeTeardown = true)
		// Capture references before super.onDestroy() cancels the coroutine scope
		val initializationRef = initializationJob
		val precedingTeardown = synchronized(SERVICE_GENERATION_LOCK) {
			lastTeardownGate
		}
		initializationRef?.cancel()
		initializationJob = null
		sessionRecoveryJob?.cancel()
		sessionRecoveryJob = null
		descriptorObservationJob?.cancel()
		descriptorObservationJob = null
		val context: Context = this
		// Close notification dispatch before asynchronous teardown. A final cycle can otherwise post
		// the same notification ID after stopForeground removes it, leaving an ongoing notification
		// that no service owns and that the user cannot dismiss.
		if (::orchestrator.isInitialized) {
			orchestrator.notificationComponent.onServiceStopped(context)
		} else {
			TrackerNotificationManager.cancelTrackingNotification(context)
		}
		stopForeground(STOP_FOREGROUND_REMOVE)
		super.onDestroy()

		// Cancel lock observation job to prevent leaks
		lockObservationJob?.cancel()
		lockObservationJob = null
		batteryObservationJob?.cancel()
		batteryObservationJob = null

		// Fire-and-forget cleanup on an independent scope to avoid blocking the main thread.
		// Replacement initialization awaits this deferred, so cleanup keeps retrying until
		// shared singleton processors and hardware producers are actually quiescent.
		val cleanupScope = kotlinx.coroutines.CoroutineScope(
			dispatchers.default + kotlinx.coroutines.SupervisorJob()
		)
		val cleanupGate = TrackingTeardownGate {
			performTeardown(context)
		}
		lateinit var cleanupJob: Deferred<Unit>
		cleanupJob = cleanupScope.async(start = CoroutineStart.LAZY) {
			var shutdownCompleted = false
			try {
				precedingTeardown?.job?.join()
				TRACKING_LIFECYCLE_BARRIER.runAfter(initializationRef) {
					recoverIncompleteTeardown(precedingTeardown)
					cleanupGate.recovery()
					clearCompletedStopCandidate()
					cleanupGate.cleanupComplete.set(true)
					shutdownCompleted = true
				}
			} finally {
				synchronized(SERVICE_GENERATION_LOCK) {
					if (activeServiceGeneration == serviceGeneration) {
						if (shutdownCompleted) {
							orchestrator.resetMetadata()
						} else {
							orchestrator.markServiceStopped()
						}
						activeServiceGeneration = 0L
					}
				}
			}
		}
		cleanupGate.job = cleanupJob
		synchronized(SERVICE_GENERATION_LOCK) {
			lastTeardownGate = cleanupGate
		}
		cleanupJob.invokeOnCompletion {
			cleanupScope.cancel()
			synchronized(SERVICE_GENERATION_LOCK) {
				if (cleanupGate.cleanupComplete.get() && lastTeardownGate === cleanupGate) {
					lastTeardownGate = null
				}
			}
		}
		cleanupJob.start()

		activityWatcherController.poke(trackerRunning = false)

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.updateShortcut(
				this@TrackerService,
				ShortcutData(
					Shortcuts.TRACKING_ID,
					R.string.shortcut_start_tracking,
					R.string.shortcut_start_tracking_long,
					com.adsamcik.tracker.shared.base.R.drawable.ic_play_circle_filled_black_24dp,
					Shortcuts.ShortcutAction.START_COLLECTION
				)
			)
		}
	}

	private suspend fun recoverIncompleteTeardown(gate: TrackingTeardownGate?) {
		if (gate == null || gate.cleanupComplete.get()) return

		gate.recovery()
		gate.cleanupComplete.set(true)
		synchronized(SERVICE_GENERATION_LOCK) {
			if (lastTeardownGate === gate) {
				lastTeardownGate = null
			}
		}
	}

	private suspend fun performTeardown(context: Context) {
		val serviceRunId = activeSessionDescriptor?.serviceRunId
		retryTrackingShutdown {
			when (val outcome = sourceSession.stop(
				reason = stopReason.name,
				preserveLogicalSession = !gracefulStopRequested,
			)) {
				SourceSessionStopOutcome.Stopped,
				SourceSessionStopOutcome.NotActive,
				-> Unit
				is SourceSessionStopOutcome.Retryable ->
					throw TrackingShutdownRetryException(outcome.code.name)
			}
		}
		serviceRunId?.let(collectionMotionController::stopSession)
		sourcePipelineRecovery.drainCommittedWork()
		val shutdownSequence = try {
			drainCyclesThenShutdown(
				drain = {
					if (::cycleDispatcher.isInitialized) {
						cycleDispatcher.closeAndDrain()
					}
				},
				cancelPendingCycles = {
					if (::cycleDispatcher.isInitialized) {
						cycleDispatcher.cancelAndJoin()
					}
				},
				shutdown = {
					retryTrackingShutdown {
						orchestrator.shutdown(context)
					}
				},
			)
		} finally {
			cycleDispatcherScope?.cancel()
			cycleDispatcherScope = null
		}
		var finalCycleCancellationFailure = shutdownSequence.cycleCancellationFailure
		if (finalCycleCancellationFailure != null && ::cycleDispatcher.isInitialized) {
			try {
				retryTrackingShutdown(
					maxAttempts = FINAL_CYCLE_CANCEL_MAX_ATTEMPTS,
					retryDelayMillis = FINAL_CYCLE_CANCEL_RETRY_DELAY_MILLIS,
					attemptTimeoutMillis = CYCLE_CANCEL_TIMEOUT_MILLIS,
				) {
					cycleDispatcher.cancelAndJoin()
				}
				finalCycleCancellationFailure = null
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				if (!e.isTrackingOperationalFailure() && e !is TrackingShutdownRetryException) throw e
				finalCycleCancellationFailure = e
			}
		}
		if (
			!shutdownSequence.drained ||
			shutdownSequence.drainFailure != null ||
			finalCycleCancellationFailure != null ||
			shutdownSequence.shutdownResult == null
		) {
			Tracebox.log.warn(TrackerTraceboxTemplates.TRACKING_SHUTDOWN_DEGRADED)
			orchestrator.enqueueDailySummaryFallback(context)
			retryTrackingShutdown(
				maxAttempts = FINAL_TEARDOWN_MAX_ATTEMPTS,
				retryDelayMillis = FINAL_TEARDOWN_INITIAL_RETRY_DELAY_MILLIS,
				maxRetryDelayMillis = FINAL_TEARDOWN_MAX_RETRY_DELAY_MILLIS,
				attemptTimeoutMillis = FINAL_TEARDOWN_ATTEMPT_TIMEOUT_MILLIS,
			) {
				orchestrator.shutdown(context)
			}
		}
		finalCycleCancellationFailure?.let { failure ->
			throw IllegalStateException(
				"Tracking cycles remained active after bounded shutdown retries",
				failure,
			)
		}
		trackingFrameEffects.detach(trackingFrameOwnerToken)
		coordinatorMetricBaseline?.let { baseline ->
			Tracebox.log.recordCoordinatorSessionMetrics(coordinatorTelemetry.snapshot() - baseline)
		}
		coordinatorMetricBaseline = null
		Tracebox.log.info(TrackerTraceboxTemplates.TRACKING_SESSION_STOPPED)
		HistoricalTrajectoryReconstructionWorker.schedule(context)
	}

	private suspend fun sourcePlanInputs(
		settings: TrackingParamsState,
		demands: List<SourceDemand>,
	): SourceSessionPlanInputs {
		val packageManager = packageManager
		val permissionCapabilities = trackingPermissionCapabilities()
		val foreground = activeForegroundRequirements
		val locationFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
		val wifiFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
		val cellFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
		val constraints = mapOf(
			SourceKind.LOCATION to SourceConstraint(
				hardwareAvailable = locationFeature,
				permissionGranted = permissionCapabilities.hasForegroundLocation,
				foregroundCapabilityLegal = foreground?.requiresLocation == true,
			),
			SourceKind.ACTIVITY to SourceConstraint(
				hardwareAvailable = Assist.isPlayServicesAvailable(this),
				permissionGranted = hasActivityPermission,
				foregroundCapabilityLegal = foreground?.requiresHealth == true,
			),
			SourceKind.STEPS to SourceConstraint(
				hardwareAvailable = hasStepCounterSensor,
				permissionGranted = hasActivityPermission,
				foregroundCapabilityLegal = foreground?.requiresHealth == true,
			),
			SourceKind.PRESSURE to SourceConstraint(hardwareAvailable = hasPressureSensor),
			SourceKind.WIFI to SourceConstraint(
				hardwareAvailable = wifiFeature,
				permissionGranted = permissionCapabilities.hasWifiScanPermissions,
			),
			SourceKind.CELL to SourceConstraint(
				hardwareAvailable = cellFeature,
				permissionGranted = hasCellScanPermission,
			),
		)
		return SourceSessionPlanInputs(
			settings = settings,
			environment = SourcePlanEnvironment(
				locationBackend = TrackerTimerManager.getSelectedLocationBackend(this),
				preciseLocationAvailable = permissionCapabilities.hasPreciseLocation,
				subscriptionIds = emptySet(),
			),
			resolutionContext = PlanResolutionContext(
				constraints = constraints,
				powerSaver = powerManager.isPowerSaveMode,
				doze = powerManager.isDeviceIdleMode,
				severeThermalPressure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
					powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
				motionProfile = collectionMotionController.policy.value.acquisitionProfile,
			),
			demands = demands,
			clockDomainId = bootClockDomainProvider.current(),
		)
	}

	private fun prepareForegroundForSourcePlan(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState,
	): Boolean {
		check(rollout.sourceOwners.getValue(SourceKind.LOCATION) == SourceOwner.EVENT) {
			"Location source must be event-owned before foreground capabilities are applied"
		}
		val descriptor = activeSessionDescriptor ?: return false
		val requirements = resolveForegroundRequirements(
			settings = settings,
			isUserInitiated = descriptor.isUserInitiated,
			isAmbient = descriptor.isAmbient,
		) ?: return false
		return ensureForegroundStarted(
			requirements = requirements,
			requiresBackgroundLocation = !descriptor.isUserInitiated && !descriptor.isAmbient,
		)
	}

	private fun resolveForegroundRequirements(
		settings: TrackingParamsState,
		isUserInitiated: Boolean,
		isAmbient: Boolean,
	): TrackerForegroundServiceRequirements? {
		val capabilities = trackingPermissionCapabilities()
		val packageManager = packageManager
		val cellFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
		return resolveTrackerForegroundServiceRequirements(
			params = settings,
			isUserInitiated = isUserInitiated,
			isAmbient = isAmbient,
			locationAvailable = capabilities.hasForegroundLocation,
			backgroundLocationAvailable = capabilities.hasBackgroundLocation,
			activityAvailable = hasActivityPermission && Assist.isPlayServicesAvailable(this),
			stepsAvailable = hasActivityPermission && hasStepCounterSensor,
			wifiAvailable = capabilities.hasWifiScan,
			cellAvailable = hasCellScanPermission && cellFeature,
			barometerAvailable = hasPressureSensor,
		)
	}

	companion object {
		private const val MOTION_POLICY_TICK_MILLIS = 30_000L
		private val SERVICE_GENERATION_LOCK = Any()
		private val SERVICE_GENERATION_COUNTER = AtomicLong()
		private val TRACKING_LIFECYCLE_BARRIER = TrackingLifecycleBarrier()

		@Volatile
		private var activeServiceGeneration: Long = 0L
		private var lastTeardownGate: TrackingTeardownGate? = null

		const val ARG_IS_USER_INITIATED = TrackerServiceContract.ARG_IS_USER_INITIATED
		const val ARG_IS_AMBIENT = TrackerServiceContract.ARG_IS_AMBIENT
		const val ARG_POLICY_TIER = TrackerServiceContract.ARG_POLICY_TIER
		private const val DEFAULT_IS_USER_INITIATED = false
		private const val TRACKING_CYCLE_QUEUE_CAPACITY = 64
		private const val CYCLE_DRAIN_TIMEOUT_MILLIS = 4_000L
		private const val CYCLE_CANCEL_TIMEOUT_MILLIS = 2_000L
		private const val PREVIOUS_TEARDOWN_WAIT_TIMEOUT_MILLIS = 40_000L
		private const val PREVIOUS_INITIALIZATION_WAIT_TIMEOUT_MILLIS = 10_000L
		private const val FINAL_CYCLE_CANCEL_MAX_ATTEMPTS = 2
		private const val FINAL_CYCLE_CANCEL_RETRY_DELAY_MILLIS = 250L
		private const val FINAL_TEARDOWN_MAX_ATTEMPTS = 3
		private const val FINAL_TEARDOWN_INITIAL_RETRY_DELAY_MILLIS = 500L
		private const val FINAL_TEARDOWN_MAX_RETRY_DELAY_MILLIS = 2_000L
		private const val FINAL_TEARDOWN_ATTEMPT_TIMEOUT_MILLIS = 5_000L
	}
}

internal enum class TrackerServiceStartRecoveryDisposition {
	RESOLVE_DURABLE_START,
	IGNORE_DUPLICATE_REDELIVERY,
	IGNORE_AFTER_GRACEFUL_STOP,
	HANDLE_START_INTENT,
}

internal fun trackerServiceStartRecoveryDisposition(
	startFlags: Int,
	hasInMemorySession: Boolean,
	gracefulStopRequested: Boolean,
	isWatchdogStart: Boolean,
): TrackerServiceStartRecoveryDisposition {
	val isRedelivery = startFlags and Service.START_FLAG_REDELIVERY != 0
	return when {
		gracefulStopRequested ->
			TrackerServiceStartRecoveryDisposition.IGNORE_AFTER_GRACEFUL_STOP
		isWatchdogStart -> TrackerServiceStartRecoveryDisposition.HANDLE_START_INTENT
		isRedelivery && hasInMemorySession ->
			TrackerServiceStartRecoveryDisposition.IGNORE_DUPLICATE_REDELIVERY
		!hasInMemorySession -> TrackerServiceStartRecoveryDisposition.RESOLVE_DURABLE_START
		else -> TrackerServiceStartRecoveryDisposition.HANDLE_START_INTENT
	}
}

internal sealed interface TrackerServiceStartRequestResolution {
	data class Begin(
		val descriptor: ActiveTrackingSessionDescriptor,
		val isRecovery: Boolean,
	) : TrackerServiceStartRequestResolution

	data class StoreFailure(val cause: Throwable) : TrackerServiceStartRequestResolution
	data object DoNotStart : TrackerServiceStartRequestResolution
}

internal fun resolveTrackerServiceStartRequest(
	storeResult: ActiveTrackingSessionStoreResult,
	requestedDescriptor: ActiveTrackingSessionDescriptor?,
): TrackerServiceStartRequestResolution = when (storeResult) {
	is ActiveTrackingSessionStoreResult.Failure ->
		TrackerServiceStartRequestResolution.StoreFailure(storeResult.cause)
	is ActiveTrackingSessionStoreResult.Success -> {
		val storedDescriptor = storeResult.descriptor
		when {
			storedDescriptor?.isRestartEligible == true ->
				TrackerServiceStartRequestResolution.Begin(storedDescriptor, isRecovery = true)
			requestedDescriptor != null ->
				TrackerServiceStartRequestResolution.Begin(requestedDescriptor, isRecovery = false)
			else -> TrackerServiceStartRequestResolution.DoNotStart
		}
	}
}

private class TrackingTeardownGate(
	val recovery: suspend () -> Unit,
) {
	lateinit var job: Deferred<Unit>
	val cleanupComplete = AtomicBoolean(false)
}

internal data class TrackingShutdownSequenceResult<T : Any>(
	val drained: Boolean,
	val drainFailure: Throwable?,
	val cycleCancellationFailure: Throwable?,
	val shutdownResult: T?,
	val shutdownFailure: Throwable?,
)

/**
 * Gives queued cycles and orchestrator teardown independent timeout budgets.
 * Teardown must run even when draining stalls or fails.
 */
internal suspend fun <T : Any> drainCyclesThenShutdown(
	drainTimeoutMillis: Long = 4_000L,
	cancelTimeoutMillis: Long = 2_000L,
	shutdownTimeoutMillis: Long = 4_000L,
	drain: suspend () -> Unit,
	cancelPendingCycles: suspend () -> Unit,
	shutdown: suspend () -> T,
): TrackingShutdownSequenceResult<T> {
	var drainFailure: Throwable? = null
	val drained = try {
		withTimeoutOrNull(drainTimeoutMillis) {
			drain()
			true
		} == true
	} catch (exception: CancellationException) {
		throw exception
	} catch (exception: Exception) {
		if (!exception.isTrackingOperationalFailure()) throw exception
		drainFailure = exception
		false
	}

	var cycleCancellationFailure: Throwable? = null
	val cyclesCancelled = try {
		withTimeoutOrNull(cancelTimeoutMillis) {
			cancelPendingCycles()
			true
		} == true
	} catch (exception: CancellationException) {
		throw exception
	} catch (exception: Exception) {
		if (!exception.isTrackingOperationalFailure()) throw exception
		cycleCancellationFailure = exception
		false
	}
	if (!cyclesCancelled && cycleCancellationFailure == null) {
		cycleCancellationFailure = IllegalStateException(
			"Tracking cycle cancellation timed out after ${cancelTimeoutMillis}ms"
		)
	}

	var shutdownFailure: Throwable? = null
	val shutdownResult = if (cyclesCancelled) {
		try {
			withTimeoutOrNull(shutdownTimeoutMillis) { shutdown() }.also { result ->
				if (result == null) {
					shutdownFailure = IllegalStateException(
						"Tracking shutdown timed out after ${shutdownTimeoutMillis}ms"
					)
				}
			}
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			if (!exception.isTrackingOperationalFailure()) throw exception
			shutdownFailure = exception
			null
		}
	} else {
		null
	}

	return TrackingShutdownSequenceResult(
		drained = drained,
		drainFailure = drainFailure,
		cycleCancellationFailure = cycleCancellationFailure,
		shutdownResult = shutdownResult,
		shutdownFailure = shutdownFailure,
	)
}

/**
 * Pure logic: the exact foreground-service declaration required by active tracking sources.
 *
 * The runtime permission prerequisites differ per type — [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION]
 * needs ACCESS_FINE/COARSE_LOCATION and [ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH] needs
 * ACTIVITY_RECOGNITION. Permission possession alone never adds a type: location is declared only
 * while a GPS trigger is active, and health only while activity or step collection is configured.
 *
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] covers only a genuine signal-only session.
 * A location-required session has no non-location fallback because it must not access GPS under an
 * inaccurate foreground declaration.
 *
 * Before Android 14 health/special-use types do not exist, so non-location sessions use the untyped
 * `startForeground` overload.
 */
@SuppressLint("InlinedApi")
internal fun foregroundServiceTypeCandidates(
	sdkInt: Int,
	requirements: TrackerForegroundServiceRequirements,
	hasLocationPermission: Boolean,
	hasActivityPermission: Boolean,
): List<Int?> {
	if (requirements.requiresLocation && !hasLocationPermission) return emptyList()
	val hasLegalLocation = requirements.requiresLocation && hasLocationPermission
	val hasLegalHealth = requirements.requiresHealth && hasActivityPermission
	val hasLegalSignals = requirements.hasSignalSources
	if (!hasLegalLocation && !hasLegalHealth && !hasLegalSignals) return emptyList()

	if (sdkInt < Build.VERSION_CODES.Q) return listOf(null)
	if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		return if (hasLegalLocation) {
			listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
		} else {
			listOf(null)
		}
	}
	if (hasLegalLocation) {
		return listOf(
			ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
				(if (hasLegalHealth) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0),
		)
	}
	return if (hasLegalHealth) {
		listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
	} else {
		listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
	}
}

/**
 * Resolves the startup policy tier before battery capping is applied.
 *
 * Automatic tracking uses [TrackerServiceApi.startService] with `isUserInitiated = false` and no
 * ambient flag after activity recognition has already confirmed movement. If location tracking is
 * enabled, that session must start GPS-capable; otherwise the AMBIENT trigger starves the policy
 * engine of frequent movement signals and GPS never starts. [TrackerServiceApi.startAmbientService]
 * is the explicit no-GPS path.
 */
internal fun resolveInitialPolicyTier(
	isUserInitiated: Boolean,
	isAmbient: Boolean,
	locationEnabled: Boolean,
	recoveredTier: PolicyTier? = null,
): PolicyTier = when {
	recoveredTier != null && recoveredTier != PolicyTier.OFF -> recoveredTier
	isUserInitiated -> PolicyTier.PRECISION
	isAmbient -> PolicyTier.AMBIENT
	locationEnabled -> PolicyTier.ACTIVE
	else -> PolicyTier.AMBIENT
}
