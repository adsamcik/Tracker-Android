package com.adsamcik.tracker.tracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.adsamcik.tracker.activity.api.registration.LocationMotionPurpose
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticFailureCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticInfoCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticRejectionCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticWarningCode
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticFailureReason
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticRejectedReason
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.pipeline.persistence.ExclusiveTrackingPersistenceLifecycleLease
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.notification.TrackerNotificationChannels
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.permission.RuntimePermissionReconciler
import com.adsamcik.tracker.tracker.permission.RuntimePermissionSnapshot
import com.adsamcik.tracker.tracker.presentation.PresentationQuiescenceResult
import com.adsamcik.tracker.tracker.presentation.SessionPresentationBinding
import com.adsamcik.tracker.tracker.presentation.SessionPresentationLifecycle
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.AutomaticTrackingStartTrigger
import com.adsamcik.tracker.tracker.resilience.LogicalTrackingLifecycleState
import com.adsamcik.tracker.tracker.resilience.LockedTrackingStartResult
import com.adsamcik.tracker.tracker.resilience.TrackingLifecycleCommandAuthority
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition
import com.adsamcik.tracker.tracker.resilience.TrackingStopCommand
import com.adsamcik.tracker.tracker.resilience.TrackingStopCandidateReason
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.tracker.source.coordinator.PlanResolutionContext
import com.adsamcik.tracker.tracker.source.location.ProtectedLocationLiveWriterRegistry
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionPlanInputs
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionReconfigureOutcome
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopCutoff
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopOutcome
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSessionOwnership
import com.adsamcik.tracker.tracker.source.coordinator.captureModeFor
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.LocationMotionAssistanceOwner
import com.adsamcik.tracker.tracker.source.control.LocationMotionScope
import com.adsamcik.tracker.tracker.source.control.LocationCollectionStrategy
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.AmbientLocationPurposeOwner
import com.adsamcik.tracker.tracker.source.projection.ActivityAutomaticStartActionRepository
import com.adsamcik.tracker.tracker.worker.HistoricalTrajectoryReconstructionWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import javax.inject.Provider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

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
	lateinit var trackingHistoryRepository: com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository

	@Inject
	lateinit var appDatabase: AppDatabase

	@Inject
	lateinit var dispatchers: DispatchersProvider

	@Inject
	@ApplicationScope
	lateinit var applicationScope: CoroutineScope

	@Inject
	lateinit var batteryAwarePolicy: BatteryAwarePolicy

	@Inject
	internal lateinit var locationLiveWriterRegistry: ProtectedLocationLiveWriterRegistry

	@Inject
	lateinit var metricDirtyTracker: com.adsamcik.tracker.stats.api.metric.MetricDirtyTracker

	@Inject
	lateinit var activeTrackingSessionStore: ActiveTrackingSessionStore

	@Inject
	lateinit var trackingLifecycleCommandAuthority: TrackingLifecycleCommandAuthority

	@Inject
	lateinit var trackingStartRequestCoordinator: DefaultTrackingStartRequestCoordinator

	@Inject
	lateinit var sourcePipelineRecovery: SourcePipelineRecovery

	@Inject
	lateinit var sourceSession: TrackerServiceSourceSession

	@Inject
	lateinit var collectionMotionController: CollectionMotionController

	@Inject
	lateinit var locationMotionAssistanceOwner: LocationMotionAssistanceOwner

	@Inject
	lateinit var backgroundLocationHost: BackgroundLocationHost

	@Inject
	lateinit var ambientLocationPurposeOwner: Provider<AmbientLocationPurposeOwner>

	@Inject
	lateinit var trackingRolloutStateStore: RoomTrackingRolloutStateStore

	@Inject
	lateinit var bootClockDomainProvider: BootClockDomainProvider

	@Inject
	lateinit var coordinatorTelemetry: TrackingCoordinatorTelemetry

	@Inject
	lateinit var trackingStartupGate: TrackingStartupGate

	@Inject
	lateinit var automaticStartActions: ActivityAutomaticStartActionRepository

	@Inject
	lateinit var runtimePermissionReconciler: RuntimePermissionReconciler

	@Inject
	lateinit var persistenceLifecycleLease: ExclusiveTrackingPersistenceLifecycleLease

	private lateinit var orchestrator: TrackingOrchestrator
	private lateinit var sessionPresentationLifecycle: SessionPresentationLifecycle

	private var lockObservationJob: Job? = null
	private var initializationJob: Job? = null
	private var batteryObservationJob: Job? = null
	private var powerSaveObservationJob: Job? = null
	private var collectionMotionObservationJob: Job? = null
	private var sessionRecoveryJob: Job? = null
	private var descriptorObservationJob: Job? = null
	private var runtimePermissionObservationJob: Job? = null
	private lateinit var cycleDispatcher: TrackingCycleDispatcher
	private var cycleDispatcherScope: CoroutineScope? = null
	private var serviceGeneration: Long = 0L

	// Kept here for intent recovery and power-save check
	@Volatile private var sessionInfo: TrackerSessionInfo? = null
	@Volatile private var activeSessionDescriptor: ActiveTrackingSessionDescriptor? = null
	@Volatile private var gracefulStopRequested = false
	@Volatile private var stopRequestGeneration = 0L
	@Volatile private var durableStopReadyGeneration = 0L
	private var latestDeliveredStartId = 0
	@Volatile private var activeExternalStop: ActiveExternalStop? = null
	@Volatile private var stopReason: TrackingStopCandidateReason = TrackingStopCandidateReason.UNKNOWN
	@Volatile private var sessionRolloutState: TrackingRolloutState? = null
	@Volatile private var sessionStartOrigin: SessionStartOrigin? = null
	@Volatile private var foregroundStarted = false
	@Volatile private var foregroundIsStartupShell = false
	private val preparedStartOwnershipMonitor = Any()
	@Volatile private var preparedStartRuntime = PreparedStartRuntimeState()
	private val startSingleFlight = AtomicBoolean(false)
	private var activeForegroundServiceType: Int? = null
	private var activeForegroundRequirements: ForegroundServiceRequirements? = null
	private val runtimePermissionReconfigurePending = AtomicBoolean(false)
	private val locationScopeId = UUID.randomUUID().toString()
	private var locationScopeStartedNanos = 0L
	private var fallbackMotionScopeId: String? = null
	private var backgroundHostObservationJob: Job? = null
	private var liveTeardownJob: Job? = null
	private var pendingBackgroundRetirement: AmbientReconciliationIdentity? = null
	override fun onCreate() {
		super.onCreate()
		synchronized(SERVICE_GENERATION_LOCK) {
			serviceGeneration = SERVICE_GENERATION_COUNTER.incrementAndGet()
			activeServiceGeneration = serviceGeneration
		}

		registerRuntimeStopDispatcher()

		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(
			PowerManager.PARTIAL_WAKE_LOCK,
			"signals:TrackerWakeLock"
		)

		sessionPresentationLifecycle = SessionPresentationLifecycle(appDatabase)
		orchestrator = createOrchestrator()
		createCycleDispatcher()
		backgroundLocationHost.attach(this) {
			launch {
				reconcileLocationMotionScope()
				refreshHostedForeground()
			}
			// A successor can acknowledge its host while a predecessor release dispatches to main.
			if (!backgroundLocationHost.hasActiveSources && sessionInfo == null &&
				!startSingleFlight.get() && liveTeardownJob?.isActive != true
			) stopSelf()
		}
		backgroundHostObservationJob = launch {
			trackingParamsRepository.data.collect {
				reconcileLocationMotionScope()
				refreshBackgroundLocationPlan()
			}
		}
		runtimePermissionObservationJob = launch {
			runtimePermissionReconciler.changes.collect {
				onRuntimePermissionChanged()
			}
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.updateShortcut(
				this,
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

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		latestDeliveredStartId = maxOf(latestDeliveredStartId, startId)
		if (intent?.action == TrackerServiceContract.ACTION_ACQUIRE_BACKGROUND_LOCATION_HOST) {
			val ticket = intent.getStringExtra(TrackerServiceContract.ARG_BACKGROUND_LOCATION_HOST_TICKET)
			if (ticket == null) return discardStartRequest(startId)
			launch {
				val accepted = backgroundLocationHost.acceptDelivery(ticket, this@TrackerService,
					locationMotionScope(setOf(LocationMotionPurpose.BACKGROUND))) { hostedSources ->
					synchronized(preparedStartOwnershipMonitor) {
						TrackerNotificationChannels.ensureTrackingChannel(this@TrackerService)
						ensureForegroundStarted(activeForegroundRequirements?.acceptedSources.orEmpty() + hostedSources,
                            stopServiceOnFailure = false,
							backgroundOnly = sessionInfo == null && !startSingleFlight.get())
					}
				}
				if (accepted) {
					controller.updateServiceRunning(true)
					reconcileLocationMotionScope()
					observeCollectionMotionPolicy()
				} else if (sessionInfo == null && !backgroundLocationHost.hasActiveSources &&
                    !startSingleFlight.get()) {
					stopSelfResult(startId)
				}
			}
			return START_NOT_STICKY
		}

		if (!intent.isExactPreparedTrackingStartAction()) {
			return discardStartRequest(startId)
		}
		val preparedToken = intent?.preparedTrackingStartTokenOrNull()
			?: return discardStartRequest(startId)
		val startCommand = intent.trackingStartCommandOrNull()
			?: return discardPreparedStart(preparedToken, null, startId, "START_COMMAND_MISSING")
		val foregroundHint = intent.preparedForegroundHintOrNull()
			?: return discardPreparedStart(
				preparedToken,
				startCommand,
				startId,
				"PREPARED_FOREGROUND_HINT_INVALID",
			)
		if (liveTeardownJob?.isActive == true) return discardPreparedStart(
			preparedToken, startCommand, startId, "PREVIOUS_LIVE_SESSION_END_PENDING",
		)
		val isPassiveAndroidDelivery = isPassivePreparedStartDelivery(flags)
		val deliveredStart = synchronized(preparedStartOwnershipMonitor) {
			resolveRebasedStartDelivery(preparedStartRuntime, preparedToken, startCommand) {
				trackingLifecycleCommandAuthority.resolveStart(startCommand)
			}
		}
		deliveredStart.predecessorStartId?.let {
			// B is now the most recently delivered start ID, so retiring A cannot stop the service.
			stopSelfResult(it)
		}

		when (val commandDisposition = deliveredStart.commandDisposition) {
			TrackingStartCommandDisposition.Allowed -> supersedeOlderExternalStop(startCommand)
			TrackingStartCommandDisposition.Stale -> return discardPreparedStart(
				preparedToken,
				startCommand,
				startId,
				"START_COMMAND_STALE_ON_DELIVERY",
			)
			is TrackingStartCommandDisposition.BlockedByStop -> {
				launch {
					trackingStartRequestCoordinator.compensate(
						preparedToken,
						startCommand,
						"START_BLOCKED_BY_STOP_ON_DELIVERY",
					)
				}
				requestGracefulStop(
					startId = startId,
					reason = commandDisposition.stop.reason,
					externalCommand = commandDisposition.stop,
				)
				return START_NOT_STICKY
			}
		}

		synchronized(preparedStartOwnershipMonitor) {
			val recoveredSessionInfo = sessionInfo ?: controller.sessionInfoFlow.value
			if (gracefulStopRequested) return discardPreparedStart(
				preparedToken,
				startCommand,
				startId,
				"START_DELIVERED_AFTER_STOP",
			)
			if (recoveredSessionInfo != null) {
				val activePrepared = preparedStartRuntime
				return if (activePrepared.matches(preparedToken, startCommand)) {
					START_REDELIVER_INTENT
				} else {
					discardPreparedStart(
						preparedToken,
						startCommand,
						startId,
						"START_DELIVERED_TO_ACTIVE_RUNTIME",
					)
				}
			}
			if (!startSingleFlight.compareAndSet(false, true)) {
				val activePrepared = preparedStartRuntime
				return if (activePrepared.matches(preparedToken, startCommand)) {
					START_REDELIVER_INTENT
				} else discardPreparedStart(
					preparedToken,
					startCommand,
					startId,
					"START_DELIVERY_SINGLE_FLIGHT_BUSY",
				)
			}
			adoptPreparedStartRuntime(preparedToken, startCommand)
			val foregroundSources = if (isPassiveAndroidDelivery) {
				resolveAcceptedForegroundSources(
					requestedSources = foregroundHint.sources,
					startOrigin = SessionStartOrigin.RECOVERY,
				)
			} else {
				foregroundHint.sources
			}
			if (foregroundSources.isEmpty() || !ensureForegroundStarted(
				acceptedSources = foregroundSources,
				stopServiceOnFailure = false,
				notificationIsUserInitiatedHint = foregroundHint.isUserInitiated,
				startupShell = true,
			)) {
				rollbackRejectedPreparedStartRuntime(preparedToken, startCommand, startId)
				return discardPreparedStart(
					preparedToken,
					startCommand,
					startId,
					"PREPARED_FOREGROUND_PROMOTION_UNAVAILABLE",
				)
			}
		}
		val startupCompletionDeadlineNanos = SystemClock.elapsedRealtimeNanos().saturatedAdd(
			STARTUP_FOREGROUND_SHELL_MAX_MILLIS * NANOS_PER_MILLISECOND,
		)
		sessionRecoveryJob = launch {
			var effectiveToken = preparedToken
			var effectiveCommand = startCommand
			try {
				if (!awaitTrackingStartupBefore(startupCompletionDeadlineNanos)) {
					abandonPreparedStartShell(effectiveToken, effectiveCommand, startId)
					return@launch
				}
				val completed = trackingStartupGate.withReadyGenerationWork(
					trackingStartupGate.currentGeneration,
				) startupWork@{
					try {
						if (isPassiveAndroidDelivery) {
							val redelivery = try {
								withTimeoutOrNull(remainingStartupMillis(startupCompletionDeadlineNanos)) {
									var resolution: AndroidRedeliveryStartResolution
									do {
										trackingStartupGate.awaitReady()
										resolution = trackingStartRequestCoordinator.resolveAndroidRedelivery(
											preparedToken,
											startCommand,
										)
									} while (resolution is AndroidRedeliveryStartResolution.Deferred)
									resolution
								}
							} catch (cancelled: CancellationException) {
								throw cancelled
							} catch (failure: Exception) {
								TrackerDiagnosticLog.failure(
									TrackerDiagnosticFailureCode.TRACKING_REDELIVERY_RESOLUTION_FAILED,
									TrackingDiagnosticFailureReason.RECOVERY_FAILURE,
								)
								AndroidRedeliveryStartResolution.Rejected("TRACKING_PRE_FOREGROUND_FAILED")
							}
							if (redelivery == null) {
								abandonPreparedStartShell(effectiveToken, effectiveCommand, startId)
								return@startupWork
							}
							when (redelivery) {
								AndroidRedeliveryStartResolution.Deferred -> error(
									"Deferred redelivery escaped the startup wait loop",
								)
								is AndroidRedeliveryStartResolution.OriginalPreparedStart -> {
									effectiveToken = redelivery.token
									effectiveCommand = redelivery.command
								}
								is AndroidRedeliveryStartResolution.Prepared -> {
									val adopted = withPreparedStartOwnership(effectiveToken, effectiveCommand) {
										supersedeOlderExternalStop(redelivery.command)
										preparedStartRuntime = PreparedStartRuntimeState(
											redelivery.preparation.token,
											redelivery.command,
											applied = false,
											pendingPredecessor = RebasedPredecessorDelivery(
												replacementToken = redelivery.preparation.token,
												replacementCommandGeneration = redelivery.command.generation,
												predecessorStartId = startId,
											),
										)
										true
									} == true
									if (!adopted) {
										rejectPreparedStart(redelivery.preparation.token, redelivery.command, startId,
											"REDELIVERY_REBASE_RUNTIME_SUPERSEDED")
										return@startupWork
									}
									effectiveToken = redelivery.preparation.token
									effectiveCommand = redelivery.command
									val rebase = withTimeoutOrNull(
										remainingStartupMillis(startupCompletionDeadlineNanos),
									) {
										rebasePreparedAndroidStart(
											command = effectiveCommand,
											validateAndEnqueue = { command, enqueue ->
												trackingLifecycleCommandAuthority.withCurrentStart(command) {
													if (gracefulStopRequested) false else enqueue()
												}
											},
											platformEnqueue = {
												withPreparedStartOwnership(effectiveToken, effectiveCommand) {
													// B owns startup as soon as Android accepts it. Releasing immediately before
													// the synchronous call lets B take the flight before its Room ack completes.
													startSingleFlight.set(false)
													val enqueued = try {
														TrackerServiceApi.enqueuePreparedStartFromRunningService(
															this@TrackerService,
															redelivery.preparation,
															effectiveCommand,
														)
													} catch (failure: RuntimeException) {
														TrackerDiagnosticLog.failure(
															TrackerDiagnosticFailureCode.TRACKING_REBASE_ENQUEUE_FAILED,
															TrackingDiagnosticFailureReason.RECOVERY_FAILURE,
														)
														false
													}
													if (enqueued) {
														preparedStartRuntime = preparedStartRuntime.copy(platformOwned =
                                                            true)
													} else {
														startSingleFlight.compareAndSet(false, true)
													}
													enqueued
												} == true
											},
											markEnqueued = {
												trackingStartRequestCoordinator.markAndroidStartEnqueued(
													effectiveToken,
													effectiveCommand,
												)
											},
										)
									}
									if (rebase == null) {
										if (preparedStartRuntime.platformOwned) return@startupWork
										withPreparedStartOwnership(effectiveToken,
                                            effectiveCommand) { startSingleFlight.compareAndSet(false, true) }
										rejectPreparedStart(
											effectiveToken,
											effectiveCommand,
											startId,
											"REDELIVERY_REBASE_TIMEOUT",
										)
										return@startupWork
									}
									when (rebase) {
										is AndroidRedeliveryRebaseResult.Rebased -> {
											if (!rebase.enqueueAcknowledged) {
												TrackerDiagnosticLog.warn(
													TrackerDiagnosticWarningCode.TRACKING_REBASE_ENQUEUE_ACK_MISSING,
												)
											}
											return@startupWork
										}
										AndroidRedeliveryRebaseResult.EnqueueFailed -> {
											withPreparedStartOwnership(effectiveToken,
                                                effectiveCommand) { startSingleFlight.compareAndSet(false, true) }
											rejectPreparedStart(
												effectiveToken,
												effectiveCommand,
												startId,
												"REDELIVERY_REBASE_ENQUEUE_FAILED",
											)
											return@startupWork
										}
										AndroidRedeliveryRebaseResult.Stale -> {
											withPreparedStartOwnership(effectiveToken,
                                                effectiveCommand) { startSingleFlight.compareAndSet(false, true) }
											rejectPreparedStart(
												effectiveToken,
												effectiveCommand,
												startId,
												"REDELIVERY_REBASE_COMMAND_STALE",
											)
											return@startupWork
										}
										is AndroidRedeliveryRebaseResult.BlockedByStop -> {
											withPreparedStartOwnership(effectiveToken,
                                                effectiveCommand) { startSingleFlight.compareAndSet(false, true) }
											requestGracefulStop(
												startId = startId,
												reason = rebase.stop.reason,
												externalCommand = rebase.stop,
											)
											rejectPreparedStart(
												effectiveToken,
												effectiveCommand,
												startId,
												"REDELIVERY_REBASE_BLOCKED_BY_STOP",
											)
											return@startupWork
										}
									}
								}
								is AndroidRedeliveryStartResolution.BlockedByStop -> {
									requestGracefulStop(
										startId = startId,
										reason = redelivery.stop.reason,
										externalCommand = redelivery.stop,
									)
									rejectPreparedStart(
										effectiveToken,
										effectiveCommand,
										startId,
										"REDELIVERY_BLOCKED_BY_STOP",
									)
									return@startupWork
								}
								is AndroidRedeliveryStartResolution.Rejected -> {
									rejectPreparedStart(
										effectiveToken,
										effectiveCommand,
										startId,
										redelivery.failureCode,
									)
									return@startupWork
								}
							}
							if (withPreparedStartOwnership(effectiveToken, effectiveCommand) {
								preparedStartRuntime = preparedStartRuntime.copy(applied = false)
							} == null) return@startupWork
						}
						var reconcileResumedPurposes = false
						val prepared = try {
							withTimeoutOrNull(remainingStartupMillis(startupCompletionDeadlineNanos)) {
								var claimed: TrackingServicePreparedStartClaim
								do {
									trackingStartupGate.awaitReady()
									claimed = trackingStartRequestCoordinator.claimForService(
										effectiveToken,
										effectiveCommand.generation,
									)
								} while (claimed is TrackingServicePreparedStartClaim.Deferred)
								if (claimed !is TrackingServicePreparedStartClaim.Claimed) return@withTimeoutOrNull claimed
								reconcileResumedPurposes = trackingLifecycleCommandAuthority
									.isPendingCollectionStart(effectiveCommand)
								trackingStartRequestCoordinator.acceptForeground(claimed, effectiveCommand) {
									withPreparedStartOwnership(effectiveToken, effectiveCommand) {
										if (gracefulStopRequested) return@withPreparedStartOwnership false
										this@TrackerService.sessionInfo =
                                            TrackerSessionInfo(claimed.claim.isUserInitiated)
										activeSessionDescriptor = claimed.descriptor
										sessionStartOrigin = claimed.claim.startOrigin
										ensureForegroundStarted(claimed.claim.acceptedSources, stopServiceOnFailure =
                                            false) &&
											foregroundPromotionMatches(
												sdkInt = Build.VERSION.SDK_INT,
												captureSources = claimed.claim.acceptedSources,
												hostedSources = backgroundLocationHost.hostedSources,
												desiredCaptureMask = claimed.claim.desiredForegroundCapabilityFlags,
												appliedSources =
                                                    activeForegroundRequirements?.acceptedSources.orEmpty(),
												appliedMask = activeForegroundServiceType?.toLong() ?: 0L,
											)
									} == true
								}
							}
						} catch (cancelled: CancellationException) {
							throw cancelled
						} catch (failure: Exception) {
							TrackerDiagnosticLog.failure(
								TrackerDiagnosticFailureCode.TRACKING_PREPARED_START_FOREGROUND_FAILED,
								TrackingDiagnosticFailureReason.INITIALIZATION_FAILURE,
							)
							TrackingServicePreparedStartClaim.Rejected("TRACKING_PRE_FOREGROUND_FAILED")
						}
						if (prepared == null) {
							abandonPreparedStartShell(effectiveToken, effectiveCommand, startId)
							return@startupWork
						}
						if (prepared !is TrackingServicePreparedStartClaim.Claimed) {
							val rejection = prepared as? TrackingServicePreparedStartClaim.Rejected
								?: error("Deferred claim escaped the startup wait loop")
							try {
								if (rejection.compensatePreparedState) {
									runBoundedStartPreparationCancellationCleanup(null) {
										trackingStartRequestCoordinator.compensate(
											effectiveToken,
											effectiveCommand,
											rejection.failureCode,
										)
									}
								}
							} finally {
								rollbackRejectedPreparedStartRuntime(effectiveToken, effectiveCommand, startId)
							}
							return@startupWork
						}
						if (reconcileResumedPurposes) {
							trackingStartRequestCoordinator.reconcileAcceptedManualStart(effectiveCommand)
						}
						beginSessionAfterStartupRecovery(
							startDescriptor = prepared.descriptor,
							startId = startId,
							isRecovery = prepared.claim.startOrigin == SessionStartOrigin.RECOVERY,
							automaticTrigger = prepared.claim.automaticTrigger,
							trackingParams = prepared.settings,
							requestedForegroundSources = prepared.claim.acceptedSources,
							initialAcceptedForegroundSources = prepared.claim.acceptedSources,
							preparedTrackingStart = prepared,
							startCommandGeneration = effectiveCommand.generation,
						)
					} catch (cancelled: CancellationException) {
						// This cleanup belongs to the registered child: Clear joins its physical
						// shell release before closing the data generation.
						rethrowAfterPreparedStartCleanup(cancelled, effectiveToken, effectiveCommand, startId)
					} finally {
						cancelPendingPreparedCollectionStart(effectiveCommand)
					}
				}
				if (completed == null) abandonPreparedStartShell(effectiveToken, effectiveCommand, startId)
			} catch (cancelled: CancellationException) {
				// Covers cancellation before Ready work was registered. A completed inner cleanup
				// is harmless here; exact matching never releases a successor delivery.
				rethrowAfterPreparedStartCleanup(cancelled, effectiveToken, effectiveCommand, startId)
			} finally {
				cancelPendingPreparedCollectionStart(effectiveCommand)
			}
		}
		return START_REDELIVER_INTENT
	}

	private suspend fun rethrowAfterPreparedStartCleanup(
		cancelled: CancellationException,
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		startId: Int,
	): Nothing {
		try {
			retireCancelledPreparedStart(token, command, startId)
		} catch (
			// Cancellation must preserve its original cause after exact-start shell cleanup.
			@Suppress("TooGenericExceptionCaught") cleanup: Exception
		) {
			cancelled.addSuppressed(cleanup)
		}
		throw cancelled
	}

	private suspend fun awaitTrackingStartupBefore(deadlineElapsedRealtimeNanos: Long): Boolean {
		if (trackingStartupGate.isReady) return true
		return withTimeoutOrNull(remainingStartupMillis(deadlineElapsedRealtimeNanos)) {
			trackingStartupGate.awaitReady()
			trackingStartupGate.isReady
		} == true
	}

	private fun remainingStartupMillis(deadlineElapsedRealtimeNanos: Long): Long {
		val remainingNanos = deadlineElapsedRealtimeNanos - SystemClock.elapsedRealtimeNanos()
		if (remainingNanos <= 0L) return 0L
		return (remainingNanos / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
	}

	/** Compensates the exact timed-out start before releasing its providerless foreground shell. */
	private suspend fun abandonPreparedStartShell(token: PreparedTrackingStartToken, command: TrackingStartCommand,
        startId: Int) {
		TrackerDiagnosticLog.failure(
			TrackerDiagnosticFailureCode.TRACKING_PREPARED_SHELL_STOPPED,
			TrackingDiagnosticFailureReason.TIMEOUT,
		)
		TrackerNotificationManager.postStartFailedNotification(this)
		try {
			compensateRejectedPreparedStart(token, command, "PREPARED_FOREGROUND_COMPLETION_TIMED_OUT")
		} finally {
			rollbackRejectedPreparedStartRuntime(token, command, startId)
		}
	}

	private suspend fun compensateRejectedPreparedStart(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		failureCode: String,
	) {
		controller.updateSessionStart(command.generation, starting = false)
		try {
			runBoundedStartPreparationCancellationCleanup(null) {
				trackingStartRequestCoordinator.compensate(token, command, failureCode)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// The exact durable start remains available for recovery if compensation cannot commit.
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.TRACKING_PREPARED_START_FOREGROUND_FAILED,
				TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	private fun adoptPreparedStartRuntime(token: PreparedTrackingStartToken, command: TrackingStartCommand) {
		synchronized(preparedStartOwnershipMonitor) {
			val current = preparedStartRuntime
			preparedStartRuntime = if (current.matches(token, command)) current.copy(applied = false)
			else PreparedStartRuntimeState(token, command, applied = false)
		}
	}

	/** Exact synchronous ownership updates; never hold this monitor over suspending work. */
	private fun <T> withPreparedStartOwnership(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		operation: () -> T,
	): T? = synchronized(preparedStartOwnershipMonitor) {
		if (preparedStartRuntime.matches(token, command)) operation() else null
	}

	private fun rollbackRejectedPreparedStartRuntime(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		startId: Int,
	): Boolean = synchronized(preparedStartOwnershipMonitor) {
		val current = preparedStartRuntime
		// APPLY may have installed a partial physical owner before its acknowledgement. Normal
		// durable Stop/init join owns that cleanup; a providerless shell rollback cannot discard it.
		if (!current.matches(token, command) || current.platformOwned || current.applyStarted || current.applied) {
			return@synchronized false
		}
		this.sessionInfo = null
		activeSessionDescriptor = null
		sessionStartOrigin = null
		preparedStartRuntime = PreparedStartRuntimeState()
		startSingleFlight.set(false)
		if (backgroundLocationHost.hasActiveSources) {
			ensureForegroundStarted(backgroundLocationHost.hostedSources, stopServiceOnFailure = false, backgroundOnly =
                true)
		} else {
			if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
			foregroundStarted = false
			foregroundIsStartupShell = false
			activeForegroundServiceType = null
			activeForegroundRequirements = null
			discardStartRequest(startId)
		}
		true
	}

	private suspend fun retireCancelledPreparedStart(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		startId: Int,
	) {
		controller.updateSessionStart(command.generation, starting = false)
		val providerless = synchronized(preparedStartOwnershipMonitor) {
			val current = preparedStartRuntime
			current.matches(token, command) && !current.platformOwned && !current.applyStarted && !current.applied
		}
		if (!providerless) return
		try {
			// A closed gate intentionally leaves the exact Room receipt for startup recovery.
			// An open gate gets the same bounded compensation as other rejected deliveries.
			runBoundedStartPreparationCancellationCleanup(null) {
				trackingStartRequestCoordinator.compensate(token, command, "START_DELIVERY_CANCELLED")
			}
		} finally {
			// Matching, coupled field clearing, foreground retirement and flight release are atomic
			// with successor adoption. Compensation itself remains outside this leaf monitor.
			rollbackRejectedPreparedStartRuntime(token, command, startId)
		}
	}

	private suspend fun cancelPendingPreparedCollectionStart(command: TrackingStartCommand) {
		withContext(NonCancellable) {
			try {
				trackingLifecycleCommandAuthority.cancelPendingCollectionStart(command.generation)
			} catch (_: Exception) {
				// Off remains saved; no restarted process restores this delivery exception.
			}
		}
	}

	private suspend fun rejectPreparedStart(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		startId: Int,
		failureCode: String,
	) {
		controller.updateSessionStart(command.generation, starting = false)
		try {
			trackingStartRequestCoordinator.compensate(token, command, failureCode)
		} finally {
			rollbackRejectedPreparedStartRuntime(token, command, startId)
		}
	}

	private fun Intent.preparedTrackingStartTokenOrNull(): PreparedTrackingStartToken? =
		getStringExtra(TrackerServiceContract.ARG_PREPARED_START_TOKEN)
			?.takeIf(String::isNotBlank)
			?.let(::PreparedTrackingStartToken)

	private fun Intent.preparedForegroundHintOrNull(): PreparedForegroundHint? {
		if (!hasExtra(TrackerServiceContract.ARG_PREPARED_USER_INITIATED_HINT)) return null
		val mask = getLongExtra(TrackerServiceContract.ARG_PREPARED_SOURCE_MASK_HINT, -1L)
		val sources = sourceKindsFromMask(mask)?.takeIf { it.isNotEmpty() } ?: return null
		return PreparedForegroundHint(
			sources = sources,
			isUserInitiated = getBooleanExtra(
				TrackerServiceContract.ARG_PREPARED_USER_INITIATED_HINT,
				false,
			),
		)
	}

	private fun discardPreparedStart(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand?,
		startId: Int,
		failureCode: String,
	): Int {
		command?.let { controller.updateSessionStart(it.generation, starting = false) }
		if (command != null) applicationScope.launch {
			compensateRejectedPreparedStart(token, command, failureCode)
		}
		return if (shouldStopServiceForDiscardedPreparedStart(
			activeRuntimePresent = sessionInfo != null || controller.sessionInfoFlow.value != null,
			startFlightInProgress = startSingleFlight.get(),
			preparedRuntimePresent = preparedStartRuntime.token != null,
		)) {
			discardStartRequest(startId)
		} else {
			// A started service cannot acknowledge one individual start ID. stopSelfResult(startId)
			// would stop an already-active A when a distinct B is the newest delivery. The durable
			// compensation above retires B; A keeps running and will eventually stop using the latest ID.
			START_NOT_STICKY
		}
	}

	private fun Intent.trackingStartCommandOrNull(): TrackingStartCommand? {
		val generation = getLongExtra(TrackerServiceContract.ARG_LIFECYCLE_COMMAND_GENERATION, 0L)
		return generation.takeIf { it > 0L }?.let(::TrackingStartCommand)
	}

	private fun beginSessionAfterStartupRecovery(
		startDescriptor: ActiveTrackingSessionDescriptor,
		startId: Int,
		isRecovery: Boolean,
		automaticTrigger: AutomaticTrackingStartTrigger?,
		trackingParams: TrackingParamsState,
		requestedForegroundSources: Set<SourceKind>,
		initialAcceptedForegroundSources: Set<SourceKind>,
		preparedTrackingStart: TrackingServicePreparedStartClaim.Claimed,
		startCommandGeneration: Long,
	) {
		withPreparedStartOwnership(preparedTrackingStart.claim.token, TrackingStartCommand(startCommandGeneration)) {
			val startCommand = TrackingStartCommand(startCommandGeneration)
			val acceptedContinuation = preparedTrackingStart.claim.isProviderStepsJoin &&
				trackingLifecycleCommandAuthority.isAcceptedStart(startCommand) &&
				trackingLifecycleCommandAuthority.pendingCollectionResumeExitId == null &&
				trackingLifecycleCommandAuthority.resolveStart(startCommand) ==
					com.adsamcik.tracker.tracker.resilience.TrackingStartCommandDisposition.Allowed
			if (gracefulStopRequested ||
				(trackingLifecycleCommandAuthority.latestCommandGeneration() != startCommandGeneration &&
                    !acceptedContinuation)) {
				return@withPreparedStartOwnership
			}
			TrackerDiagnosticLog.info(
				TrackerDiagnosticInfoCode.TRACKING_SESSION_START_REQUESTED,
			)
			gracefulStopRequested = false
			stopRequestGeneration++
			stopReason = TrackingStopCandidateReason.UNKNOWN
			// A restart retains the logical session identity but is a distinct Android-service run.
			val serviceRunDescriptor = preparedTrackingStart.descriptor
			val isUserInitiated = serviceRunDescriptor.isUserInitiated
			val isAmbient = serviceRunDescriptor.isAmbient
			val startOrigin = when {
				isRecovery -> SessionStartOrigin.RECOVERY
				isUserInitiated -> SessionStartOrigin.MANUAL_FOREGROUND_START
				else -> SessionStartOrigin.AUTOMATIC_BACKGROUND_START
			}
			sessionStartOrigin = startOrigin
			val provisionalDescriptor = serviceRunDescriptor.copy(
				policyTier = resolveInitialPolicyTier(
					isUserInitiated = isUserInitiated,
					isAmbient = isAmbient,
					locationEnabled = false,
					recoveredTier = startDescriptor.policyTier.takeUnless { it == PolicyTier.OFF },
				),
			)
			activeSessionDescriptor = provisionalDescriptor

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
				val preparedToken = preparedTrackingStart.claim.token
				val preparedCommand = TrackingStartCommand(startCommandGeneration)
				suspend fun compensatePreparedStart(failureCode: String) {
					controller.updateSessionStart(preparedCommand.generation, starting = false)
					if (withPreparedStartOwnership(preparedToken,
                        preparedCommand) { !preparedStartRuntime.applied } != true) return
					trackingStartRequestCoordinator.compensate(
						preparedTrackingStart.claim.token,
						TrackingStartCommand(startCommandGeneration),
						failureCode,
					)
				}
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
						if (withPreparedStartOwnership(preparedToken,
                            preparedCommand) { !gracefulStopRequested } != true) return@runAfter
						if (!saveActiveSession(provisionalDescriptor)) return@runAfter

						// Resolve semantic source plans before starting the event-owned session runtimes.
						val rolloutState = trackingRolloutStateStore.load()
						if (withPreparedStartOwnership(preparedToken, preparedCommand) {
							sessionRolloutState = rolloutState
						} == null) return@runAfter
						val currentAcceptedForegroundSources = resolveAcceptedForegroundSources(
							requestedForegroundSources,
							startOrigin,
						)
						if (automaticTrigger != null &&
							currentAcceptedForegroundSources != initialAcceptedForegroundSources
						) {
							automaticStartActions.markTerminalExact(
								automaticTrigger,
								System.currentTimeMillis(),
								"AUTOMATIC_START_CAPABILITIES_CHANGED_BEFORE_COMMIT",
							)
							compensatePreparedStart("AUTOMATIC_START_CAPABILITIES_CHANGED_BEFORE_APPLY")
							activeTrackingSessionStore.clearExact(provisionalDescriptor)
							withPreparedStartOwnership(preparedToken, preparedCommand) {
								activeSessionDescriptor = null
								startSingleFlight.set(false)
								requestGracefulStop(startId, TrackingStopCandidateReason.PERMISSION_UNAVAILABLE)
							}
							return@runAfter
						}
						val acceptedForegroundSources = currentAcceptedForegroundSources
						if (acceptedForegroundSources.isEmpty()) {
							compensatePreparedStart("TRACKING_CAPTURE_UNAVAILABLE_BEFORE_APPLY")
							activeTrackingSessionStore.clearExact(provisionalDescriptor)
							withPreparedStartOwnership(preparedToken, preparedCommand) {
								activeSessionDescriptor = null
								startSingleFlight.set(false)
								requestGracefulStop(startId, TrackingStopCandidateReason.CAPTURE_UNAVAILABLE)
							}
							return@runAfter
						}
						val locationEnabled = SourceKind.LOCATION in acceptedForegroundSources
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
						if (withPreparedStartOwnership(preparedToken, preparedCommand) {
							!gracefulStopRequested && ensureForegroundStarted(acceptedForegroundSources)
						} != true) {
							compensatePreparedStart("TRACKING_FOREGROUND_LOST_BEFORE_APPLY")
							return@runAfter
						}
						var descriptor = provisionalDescriptor.copy(policyTier = initialTier)
						if (withPreparedStartOwnership(preparedToken, preparedCommand) {
							activeSessionDescriptor = descriptor
						} == null) return@runAfter
						if (descriptor != provisionalDescriptor) {
							saveActiveSession(descriptor)
						}
						if (withPreparedStartOwnership(preparedToken, preparedCommand) {
							if (gracefulStopRequested) false else {
								preparedStartRuntime = preparedStartRuntime.copy(applyStarted = true)
								true
							}
						} != true) return@runAfter
						val sourceStartFailure: Any? = when (val result = trackingStartRequestCoordinator.apply(
							preparedTrackingStart,
							startCommandGeneration,
						)) {
							is com.adsamcik.tracker.tracker.source.coordinator.SessionStartResult.Started -> {
								if (withPreparedStartOwnership(preparedToken, preparedCommand) {
									preparedStartRuntime = preparedStartRuntime.copy(applied = true)
								} == null) return@runAfter
								val appliedReference = result.sourceCallerAuthorityReference
									?: descriptor.sourceCallerAuthorityReference
								val persistedDescriptor = appliedReference?.let { reference ->
									sourceSession.persistedDescriptorForActiveSession(reference)
								}
								if (persistedDescriptor == null) {
									"APPLIED_SOURCE_PLAN_DESCRIPTOR_MISSING"
								} else {
									descriptor = persistedDescriptor
									if (withPreparedStartOwnership(preparedToken, preparedCommand) {
										activeSessionDescriptor = persistedDescriptor
									} == null) return@runAfter
									null
								}
							}
							null -> "TRACKING_STARTUP_GENERATION_CLOSED_BEFORE_APPLY"
							else -> result
						}
						if (sourceStartFailure != null) {
							compensatePreparedStart("PREPARED_SOURCE_APPLY_REJECTED")
							activeTrackingSessionStore.clearExact(descriptor)
							withPreparedStartOwnership(preparedToken, preparedCommand) {
								activeSessionDescriptor = null
								startSingleFlight.set(false)
								TrackerDiagnosticLog.rejected(
									TrackerDiagnosticRejectionCode
										.TRACKING_SOURCE_SESSION_START_REJECTED,
									TrackingDiagnosticRejectedReason.START_NOT_AUTHORIZED,
								)
								requestGracefulStop(
									startId,
									TrackingStopCandidateReason.INITIALIZATION_FAILURE,
								)
							}
							return@runAfter
						}

						if (withPreparedStartOwnership(preparedToken, preparedCommand) {
							if (gracefulStopRequested) false else {
								controller.updateServiceRunning(true)
								this@TrackerService.sessionInfo = TrackerSessionInfo(isUserInitiated)
								controller.updateSessionInfo(this@TrackerService.sessionInfo)
								controller.updateSessionStart(preparedCommand.generation, starting = false)
								updateLiveTrackingShortcut(active = true)
								startSingleFlight.set(false)
								true
							}
						} != true) return@runAfter
						if (runtimePermissionReconfigurePending.getAndSet(false)) {
							reconfigureActiveSessionForRuntimePermissions()
						}
						// A user-initiated restart must also remove the automatic-session observer;
						// otherwise a later lock emission can stop the replacement user session.
						lockObservationJob?.cancel()
						lockObservationJob = null
						if (!isUserInitiated) {
							lockObservationJob = launch {
								lockManager.isLockedFlow.collect { isLocked ->
									if (isLocked) {
										requestGracefulStop(
											reason = TrackingStopCandidateReason.DEVICE_LOCKED,
										)
									}
								}
							}
						}
						controller.updatePolicyTier(initialTier)
						if (!quiesceCycleDispatcherForReplacement()) {
							requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
							return@runAfter
						}
						createCycleDispatcher()
						val presentationBinding = withContext(dispatchers.default) {
							orchestrator.initialize(
								context = this@TrackerService,
								isSessionUserInitiated = isUserInitiated,
								initialTier = initialTier,
								scope = this@TrackerService,
								logicalTrackingId = descriptor.logicalTrackingId,
								serviceRunId = descriptor.serviceRunId,
								resumeSessionSegmentId = descriptor.sessionSegmentId,
								rolloutState = rolloutState,
							)
						}
						descriptor = mirrorPresentationBinding(descriptor, presentationBinding)
						locationLiveWriterRegistry.attach(orchestrator)
						// Initial callbacks may arrive before the presentation segment is bound.
						sourcePipelineRecovery.requestProtectedLocationCanonicalDrain()
						sourcePipelineRecovery.requestStepsSessionFactDrain()
						sourcePipelineRecovery.requestPressureSessionFactDrain()
						sourcePipelineRecovery.requestActivityCapturedFactDrain()
						sourcePipelineRecovery.requestWifiSessionFactDrain()
						sourcePipelineRecovery.requestCellSessionFactDrain()
						observeDescriptorTierChanges(descriptor)
						reconcileLocationMotionScope()
						sourcePipelineRecovery.drainCommittedWork()
						observeCollectionMotionPolicy()
						observePowerSavePolicy()

						batteryObservationJob?.cancel()
						batteryObservationJob = launch {
							batteryAwarePolicy.batteryLevelUpdates.collect {
								orchestrator.onBatteryLevelChanged(scope = this@TrackerService)
								val currentSettings = trackingParamsRepository.data.first()
								val sourceResult = reconfigureSourceSession(
									sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
								)
								if (sourceResult == null || sourceResult is SourceSessionReconfigureOutcome.Rejected) {
									requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
								}
							}
						}
						TrackerDiagnosticLog.info(TrackerDiagnosticInfoCode.TRACKING_SESSION_STARTED)

					}
				} catch (e: TimeoutCancellationException) {
					withContext(NonCancellable) {
						compensatePreparedStart("PREPARED_START_INITIALIZATION_TIMEOUT")
					}
					TrackerDiagnosticLog.failure(
						TrackerDiagnosticFailureCode.TRACKING_START_FAILED,
						TrackingDiagnosticFailureReason.TIMEOUT,
					)
					withPreparedStartOwnership(preparedToken, preparedCommand) {
						startSingleFlight.set(false)
						requestGracefulStop(reason = TrackingStopCandidateReason.INITIALIZATION_FAILURE)
					}
				} catch (e: CancellationException) {
					withContext(NonCancellable) {
						compensatePreparedStart("PREPARED_START_INITIALIZATION_CANCELLED")
					}
					throw e
				} catch (e: Exception) {
					compensatePreparedStart("PREPARED_START_INITIALIZATION_FAILED")
					TrackerDiagnosticLog.failure(
						TrackerDiagnosticFailureCode.TRACKING_START_FAILED,
						TrackingDiagnosticFailureReason.INITIALIZATION_FAILURE,
					)
					withPreparedStartOwnership(preparedToken, preparedCommand) {
						startSingleFlight.set(false)
						requestGracefulStop(reason = TrackingStopCandidateReason.INITIALIZATION_FAILURE)
					}
				}
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
					val expected = activeSessionDescriptor
						?.takeIf {
							it.logicalTrackingId == initialDescriptor.logicalTrackingId &&
								it.serviceRunId == initialDescriptor.serviceRunId &&
								it.lifecycleState == LogicalTrackingLifecycleState.ACTIVE
						}
						?: return@collect
					val updated = expected.copy(policyTier = tier)
					if (updated == expected) return@collect
					when (val result = activeTrackingSessionStore.replaceExact(expected, updated)) {
						is ActiveTrackingSessionStoreResult.Failure -> TrackerDiagnosticLog.failure(
							TrackerDiagnosticFailureCode.TRACKING_SESSION_STORE_FAILED,
							TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
						)
						is ActiveTrackingSessionStoreResult.Success -> {
							synchronized(preparedStartOwnershipMonitor) {
								if (result.descriptor == updated && activeSessionDescriptor == expected) {
									activeSessionDescriptor = updated
								}
							}
						}
					}
				}
		}
	}

	private suspend fun mirrorPresentationBinding(
		descriptor: ActiveTrackingSessionDescriptor,
		binding: SessionPresentationBinding?,
	): ActiveTrackingSessionDescriptor {
		val exactBinding = requireNotNull(binding) {
			"Durable tracking session did not return a presentation binding"
		}
		check(
			exactBinding.logicalTrackingId == descriptor.logicalTrackingId &&
				exactBinding.serviceRunId == descriptor.serviceRunId,
		) { "Presentation binding belongs to another tracking run" }
		val boundDescriptor = descriptor.copy(sessionSegmentId = exactBinding.sessionSegmentId)
		return when (
			val result = activeTrackingSessionStore.bindSessionSegmentIfCurrent(
				expected = descriptor,
				sessionSegmentId = exactBinding.sessionSegmentId,
			)
		) {
			is ActiveTrackingSessionStoreResult.Failure -> {
				// Room already owns the exact tuple. A failed DataStore mirror reduces recovery
				// convenience but must not discard an otherwise accepted source session.
				TrackerDiagnosticLog.failure(
					TrackerDiagnosticFailureCode.TRACKING_SESSION_STORE_FAILED,
					TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
				)
				descriptor
			}
			is ActiveTrackingSessionStoreResult.Success -> {
				check(result.descriptor == boundDescriptor) {
					"Tracking descriptor changed before presentation binding could be mirrored"
				}
				synchronized(preparedStartOwnershipMonitor) {
					check(activeSessionDescriptor == descriptor) {
						"In-memory tracking descriptor changed during presentation binding"
					}
					boundDescriptor.also { activeSessionDescriptor = it }
				}
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
						refreshBackgroundLocationPlan()
						if (sessionInfo == null || gracefulStopRequested) return@collect
						val currentSettings = trackingParamsRepository.data.first()
						val result = reconfigureSourceSession(
							sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
						)
						if (result == null || result is SourceSessionReconfigureOutcome.Rejected) {
							requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
						}
					}
			}
			while (isActive) {
				delay(MOTION_POLICY_TICK_MILLIS)
				reconcileCollectionMotionTick()
			}
		}
	}

	private fun registerRuntimeStopDispatcher() {
		TrackerRuntimeStopDispatcher.register(this) { command ->
			check(Looper.myLooper() == Looper.getMainLooper()) {
				"Tracker stop ownership must be acknowledged on the main thread"
			}
			if (!trackingLifecycleCommandAuthority.isStopActionable(command)) {
				false
			} else if (!preparedStartRuntime.applyStarted) {
				// A providerless/claimed-only shell does not own the durable Room lifecycle. Stop the
				// Android shell locally and let the inactive handler finalize the exact stored run.
				if (!backgroundLocationHost.hasActiveSources) requestGracefulStop(reason = command.reason)
				false
			} else {
				requestGracefulStop(
					reason = command.reason,
					externalCommand = command,
				)
				true
			}
		}
	}

	private fun createOrchestrator(): TrackingOrchestrator = TrackingOrchestrator(
		controller = controller,
		signalProcessors = signalProcessors,
		domainEventRepository = domainEventRepository,
		dispatchers = dispatchers,
		appDatabase = appDatabase,
		trackingParamsRepository = trackingParamsRepository,
		trackingHistoryRepository = trackingHistoryRepository,
		trackingRolloutStateStore = trackingRolloutStateStore,
		persistenceLifecycleLease = persistenceLifecycleLease,
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
				reconfigureSourceSession(sourcePlanInputs(settings, demands))
			} else {
				null
			}
			if (reconfigured == null || reconfigured is SourceSessionReconfigureOutcome.Rejected) {
				requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
			}
		},
	)

	private suspend fun reconcileCollectionMotionTick() {
		try {
			// Reuse the existing low-frequency policy tick. Android normally terminates the
			// process on runtime-permission revocation; this closes the documented edge case
			// where it does not, without adding a wakeup source or hidden API dependency.
			runtimePermissionReconciler.reconcile(
				RuntimePermissionSnapshot.capture(this@TrackerService),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode
					.TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED,
				TrackingDiagnosticFailureReason.PERMISSION_RECONCILIATION_FAILURE,
			)
		}
		reconcileLocationMotionScope()
		refreshBackgroundLocationPlan()
		// Re-evaluate current power/thermal inputs as well as pending provider work.
		// The source-session fingerprint keeps an unchanged tick a no-op.
		val policyOutcome = if (sessionInfo != null && !gracefulStopRequested) {
			reconfigureSourceSession(
				sourcePlanInputs(trackingParamsRepository.data.first(), orchestrator.currentSourceDemands()),
			)
		} else null
		if (policyOutcome is SourceSessionReconfigureOutcome.Rejected) {
			requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
		}
		collectionMotionController.tick(SystemClock.elapsedRealtimeNanos())
	}

	private fun currentSourceConstraints(
		packageManager: PackageManager,
		permissionCapabilities: com.adsamcik.tracker.shared.base.extension.TrackingPermissionCapabilities,
		foreground: ForegroundServiceRequirements?,
		startOrigin: SessionStartOrigin,
	): Map<SourceKind, SourceConstraint> {
		val locationStartLegal = isLocationSourceLegalForStartOrigin(
			sdkInt = Build.VERSION.SDK_INT,
			startOrigin = startOrigin,
			hasForegroundLocationPermission = hasLocationPermission,
			hasBackgroundLocationPermission = hasBackgroundLocationPermission,
		)
		val locationFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
		val wifiFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
		val cellFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
		return mapOf(
			SourceKind.LOCATION to SourceConstraint(
				hardwareAvailable = locationFeature,
				permissionGranted = hasLocationPermission,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.LOCATION) == true,
				backgroundStartLegal = locationStartLegal,
			),
			SourceKind.ACTIVITY to SourceConstraint(
				hardwareAvailable = Assist.isPlayServicesAvailable(this),
				permissionGranted = hasActivityPermission,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.ACTIVITY) == true,
			),
			SourceKind.STEPS to SourceConstraint(
				hardwareAvailable = hasStepCounterSensor,
				permissionGranted = hasActivityPermission,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.STEPS) == true,
			),
			SourceKind.PRESSURE to SourceConstraint(
				hardwareAvailable = hasPressureSensor,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.PRESSURE) == true,
			),
			SourceKind.WIFI to SourceConstraint(
				hardwareAvailable = wifiFeature,
				permissionGranted = permissionCapabilities.hasWifiScanPermissions,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.WIFI) == true,
			),
			SourceKind.CELL to SourceConstraint(
				hardwareAvailable = cellFeature,
				permissionGranted = hasCellScanPermission,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.CELL) == true,
			),
		)
	}

	private fun SourceConstraint.acceptsLiveSource(): Boolean =
		foregroundCapabilityLegal && permissionGranted && hardwareAvailable && backgroundStartLegal

	private fun locationMotionScope(purposes: Set<LocationMotionPurpose>): LocationMotionScope {
		if (locationScopeStartedNanos == 0L) locationScopeStartedNanos = SystemClock.elapsedRealtimeNanos()
		return LocationMotionScope(locationScopeId, bootClockDomainProvider.current(),
			locationScopeStartedNanos, "tracker-host:$locationScopeId", purposes)
	}

	private fun updateLiveTrackingShortcut(active: Boolean) {
		Shortcuts.updateShortcut(this, ShortcutData(Shortcuts.TRACKING_ID,
			if (active) R.string.shortcut_end_session else R.string.shortcut_start_tracking,
			if (active) R.string.shortcut_end_session_long else R.string.shortcut_start_tracking_long,
			if (active) com.adsamcik.tracker.shared.base.R.drawable.ic_pause_circle_filled_black_24dp
			else com.adsamcik.tracker.shared.base.R.drawable.ic_play_circle_filled_black_24dp,
			if (active) Shortcuts.ShortcutAction.STOP_COLLECTION else Shortcuts.ShortcutAction.START_COLLECTION))
	}

	private suspend fun reconcileLocationMotionScope() {
		val purposes = mutableSetOf<LocationMotionPurpose>()
		if (backgroundLocationHost.active.value != null && hasLocationPermission) {
			purposes += LocationMotionPurpose.BACKGROUND
		}
		val descriptor = activeSessionDescriptor
		val rollout = sessionRolloutState
		addCaptureLocationMotionPurpose(purposes, descriptor, rollout)
		locationMotionAssistanceOwner.reconcile(purposes.takeIf { it.isNotEmpty() }?.let(::locationMotionScope))
		settleFallbackCollectionMotionScope(purposes, descriptor)
	}

	private suspend fun addCaptureLocationMotionPurpose(
		purposes: MutableSet<LocationMotionPurpose>,
		descriptor: ActiveTrackingSessionDescriptor?,
		rollout: TrackingRolloutState?,
	) {
		if (descriptor != null && rollout != null && hasLiveLocationMotionCapture()) {
			val settings = trackingParamsRepository.data.first()
			if (SourceKind.LOCATION in resolveActiveSessionOwnership(rollout, settings, descriptor).enabledEventSources) {
				purposes += LocationMotionPurpose.CAPTURE
			}
		}
	}

	private fun hasLiveLocationMotionCapture(): Boolean =
		sessionInfo != null && !gracefulStopRequested && hasLocationPermission

	private fun settleFallbackCollectionMotionScope(
		purposes: Set<LocationMotionPurpose>,
		descriptor: ActiveTrackingSessionDescriptor?,
	) {
		if (purposes.isNotEmpty()) {
			fallbackMotionScopeId = null
		} else if ((descriptor != null && sessionInfo != null && !gracefulStopRequested) ||
			backgroundLocationHost.hasActiveSources
		) {
			// Admitted Steps are deliberately sessionless. Use the existing collection scope so their
			// cheap motion hints remain useful to every participating source without requesting Location.
			val scope = locationMotionScope(setOf(LocationMotionPurpose.BACKGROUND))
			collectionMotionController.configureScope(scope.scopeId, scope.clockDomainId, scope.effectiveSinceNanos)
			fallbackMotionScopeId = scope.scopeId
		} else {
			fallbackMotionScopeId?.let(collectionMotionController::stopScope)
			fallbackMotionScopeId = null
		}
	}

	/** Recompute the type union after an independent background lease leaves. */
	private suspend fun refreshHostedForeground() {
		if (gracefulStopRequested || liveTeardownJob?.isActive == true) return
		val rollout = sessionRolloutState
		if (sessionInfo != null && rollout != null) {
			prepareForegroundForSourcePlan(trackingParamsRepository.data.first(), rollout)
		} else if (backgroundLocationHost.hasActiveSources) {
			synchronized(preparedStartOwnershipMonitor) {
				if (sessionInfo == null && !startSingleFlight.get() && preparedStartRuntime.token == null) {
					ensureForegroundStarted(backgroundLocationHost.hostedSources, stopServiceOnFailure = false,
                        backgroundOnly = true)
				}
			}
		}
	}

	private suspend fun refreshBackgroundLocationPlan() {
		if (backgroundLocationHost.active.value == null) return
		try {
			ambientLocationPurposeOwner.get().refreshActivePlan()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			// Purpose reconciliation owns its unavailable/retry state; never manufacture host success.
		}
	}

	/** Reuses the normal writer/provider retirement boundary while retaining the independent host. */
	private suspend fun finishLiveSessionKeepingBackground() {
		val retiring = synchronized(preparedStartOwnershipMonitor) { preparedStartRuntime }
		lockObservationJob?.cancel()
		lockObservationJob = null
		batteryObservationJob?.cancel()
		batteryObservationJob = null
		TRACKING_LIFECYCLE_BARRIER.runAfter(initializationJob) {
			retryTrackingShutdownUntilSuccess(
				retryDelayMillis = PERSISTENT_TEARDOWN_INITIAL_RETRY_DELAY_MILLIS,
				maxRetryDelayMillis = PERSISTENT_TEARDOWN_MAX_RETRY_DELAY_MILLIS,
				onFailure = { _, _ -> },
			) { performTeardown(this@TrackerService) }
			clearCompletedStopCandidate()
			trackingStartupGate.reconcile()
			orchestrator.resetMetadata(keepCollectionHost = backgroundLocationHost.hasActiveSources)
			updateLiveTrackingShortcut(active = false)
			val cleared = synchronized(preparedStartOwnershipMonitor) {
				if (preparedStartRuntime.token != retiring.token ||
                    preparedStartRuntime.command != retiring.command) false else {
					sessionInfo = null
					activeSessionDescriptor = null
					sessionRolloutState = null
					sessionStartOrigin = null
					fallbackMotionScopeId = null
					preparedStartRuntime = PreparedStartRuntimeState()
					activeExternalStop = null
					gracefulStopRequested = false
					startSingleFlight.set(false)
					true
				}
			}
			if (!cleared) return@runAfter
			val leaseCurrent = backgroundLocationHost.isCurrentLeaseValid()
			val hosted = synchronized(preparedStartOwnershipMonitor) {
				if (preparedStartRuntime.token != null || startSingleFlight.get()) null else {
					leaseCurrent && ensureForegroundStarted(backgroundLocationHost.hostedSources, stopServiceOnFailure =
                        false, backgroundOnly = true)
				}
			} ?: return@runAfter
			if (hosted) {
				reconcileLocationMotionScope()
				refreshBackgroundLocationPlan()
				observeCollectionMotionPolicy()
			} else {
				backgroundLocationHost.leases.value.values.forEach { backgroundLocationHost.release(it.identity) }
				stopSelf()
			}
		}
	}

	private fun observePowerSavePolicy() {
		powerSaveObservationJob?.cancel()
		powerSaveObservationJob = launch {
			batteryAwarePolicy.powerSaveModeUpdates.collect {
				if (gracefulStopRequested) return@collect
				refreshBackgroundLocationPlan()
				if (sessionInfo == null) return@collect
				val currentSettings = trackingParamsRepository.data.first()
				val result = reconfigureSourceSession(
					sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
				)
				if (result is SourceSessionReconfigureOutcome.Rejected) {
					requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
				}
			}
		}
	}

	private suspend fun onRuntimePermissionChanged() {
		val legalHosts = resolveAcceptedForegroundSources(backgroundLocationHost.hostedSources,
			SessionStartOrigin.POLICY_RECONCILIATION)
		backgroundLocationHost.leases.value.values.filter { lease ->
			lease.identity.source.canonicalSource.stableCode !in legalHosts.map { it.stableCode }
		}.forEach { backgroundLocationHost.release(it.identity) }
		reconcileLocationMotionScope()
		if (!hasLocationPermission) backgroundLocationHost.active.value?.identity?.let { backgroundLocationHost.release(it) }
		if (gracefulStopRequested) return
		if (!controller.isServiceRunning || sessionInfo == null) {
			runtimePermissionReconfigurePending.set(true)
			return
		}
		reconfigureActiveSessionForRuntimePermissions()
	}

	private suspend fun reconfigureActiveSessionForRuntimePermissions() {
		var acceptedCaptureSources: Set<SourceKind>? = null
		val outcome = try {
			val currentSettings = trackingParamsRepository.data.first()
			acceptedCaptureSources = refreshForegroundForRuntimePermissions(currentSettings)
			reconfigureSourceSession(
				sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED,
				TrackingDiagnosticFailureReason.PERMISSION_RECONCILIATION_FAILURE,
			)
			null
		}

		if (shouldStopAfterRuntimePermissionReconfigure(outcome, acceptedCaptureSources?.size)) {
			requestGracefulStop(
				reason = if (acceptedCaptureSources?.isEmpty() == true) {
					TrackingStopCandidateReason.CAPTURE_UNAVAILABLE
				} else {
					TrackingStopCandidateReason.INTERNAL_FAILURE
				},
			)
		}
	}

	private suspend fun reconfigureSourceSession(
		inputs: SourceSessionPlanInputs,
	): SourceSessionReconfigureOutcome =
		reconcileSourceSessionOutcome(sourceSession.reconfigure(inputs))

	private suspend fun reconcileSourceSessionOutcome(
		outcome: SourceSessionReconfigureOutcome,
	): SourceSessionReconfigureOutcome {
		val reference = when (outcome) {
			is SourceSessionReconfigureOutcome.Applied ->
				outcome.result.sourceCallerAuthorityReference
			is SourceSessionReconfigureOutcome.Started ->
				outcome.result.sourceCallerAuthorityReference
			is SourceSessionReconfigureOutcome.Rejected ->
				(outcome.result as? com.adsamcik.tracker.tracker.source.coordinator
					.SessionReconfigureResult.Failed)
					?.sourceCallerAuthorityReference
			is SourceSessionReconfigureOutcome.Retryable,
			is SourceSessionReconfigureOutcome.Deferred,
			SourceSessionReconfigureOutcome.NotActive,
			SourceSessionReconfigureOutcome.Unchanged,
			-> null
		} ?: return outcome
		val persisted = sourceSession.persistedDescriptorForActiveSession(reference)
			?: return sourceCallerDescriptorRejection("SOURCE_CALLER_DESCRIPTOR_MISSING")
		val published = publishReconfiguredSourceDescriptor(persisted)
		return if (published) outcome else sourceCallerDescriptorRejection("SOURCE_CALLER_DESCRIPTOR_SUPERSEDED")
	}

	private fun publishReconfiguredSourceDescriptor(
		persisted: ActiveTrackingSessionDescriptor,
	): Boolean = synchronized(preparedStartOwnershipMonitor) {
		val current = activeSessionDescriptor
		if (current == null || current.logicalTrackingId != persisted.logicalTrackingId ||
			current.serviceRunId != persisted.serviceRunId
		) {
			false
		} else {
			activeSessionDescriptor = persisted
			true
		}
	}

	private fun sourceCallerDescriptorRejection(code: String) =
		SourceSessionReconfigureOutcome.Rejected(
			com.adsamcik.tracker.tracker.source.coordinator.SessionReconfigureResult.InvalidState(
				code,
			),
		)

	private fun refreshForegroundForRuntimePermissions(settings: TrackingParamsState): Set<SourceKind>? {
		synchronized(preparedStartOwnershipMonitor) {
			val rollout = sessionRolloutState ?: return null
			val descriptor = activeSessionDescriptor ?: return emptySet()
			val ownership = resolveActiveSessionOwnership(rollout, settings, descriptor)
			val accepted = resolveAcceptedForegroundSources(
				requestedSources = ownership.enabledEventSources,
				startOrigin = sessionStartOrigin ?: SessionStartOrigin.POLICY_RECONCILIATION,
			)
			if (accepted.isEmpty()) return accepted
			return if (ensureForegroundStarted(accepted, stopServiceOnFailure = false)) {
				accepted
			} else {
				emptySet()
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
	private fun ensureForegroundStarted(
		acceptedSources: Set<SourceKind>,
		stopServiceOnFailure: Boolean = true,
		notificationIsUserInitiatedHint: Boolean? = null,
		startupShell: Boolean = false,
		backgroundOnly: Boolean = false,
	): Boolean = synchronized(preparedStartOwnershipMonitor) {
		if (acceptedSources.isEmpty()) {
			onForegroundStartFailed(stopServiceOnFailure)
			return@synchronized false
		}
		val combinedSources = acceptedSources + backgroundLocationHost.hostedSources
		val requirements = ForegroundServiceRequirements(combinedSources)

		if (requirements.requiresLocation && !hasLocationPermission) {
			onForegroundStartFailed(stopServiceOnFailure)
			return@synchronized false
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			startLegacyForeground(requirements, combinedSources, stopServiceOnFailure,
				notificationIsUserInitiatedHint, startupShell, backgroundOnly)
		} else {
			startTypedForeground(requirements, combinedSources, stopServiceOnFailure,
				notificationIsUserInitiatedHint, startupShell, backgroundOnly)
		}
	}

	private fun startLegacyForeground(
		requirements: ForegroundServiceRequirements,
		combinedSources: Set<SourceKind>,
		stopServiceOnFailure: Boolean,
		notificationIsUserInitiatedHint: Boolean?,
		startupShell: Boolean,
		backgroundOnly: Boolean,
	): Boolean {
		if (!backgroundOnly && foregroundStarted && activeForegroundRequirements == requirements &&
			foregroundIsStartupShell == startupShell
		) {
			return true
		}
		val notification = if (backgroundOnly) {
			TrackerNotificationManager.getBackgroundCollectionForegroundNotification(this, combinedSources)
		} else {
			foregroundNotification(requirements, notificationIsUserInitiatedHint, startupShell)
		}
		return if (tryStartForeground(notification, type = null)) {
			recordForegroundPromotion(null, requirements, startupShell)
		} else {
			onForegroundStartFailed(stopServiceOnFailure)
			false
		}
	}

	private fun startTypedForeground(
		requirements: ForegroundServiceRequirements,
		combinedSources: Set<SourceKind>,
		stopServiceOnFailure: Boolean,
		notificationIsUserInitiatedHint: Boolean?,
		startupShell: Boolean,
		backgroundOnly: Boolean,
	): Boolean {
		val candidates = foregroundServiceTypeCandidates(
			sdkInt = Build.VERSION.SDK_INT,
			acceptedSources = combinedSources,
		)
		val preferredType = candidates.firstOrNull()
		if (preferredType == null && candidates.isEmpty()) {
			onForegroundStartFailed(stopServiceOnFailure)
			return false
		}
		if (hasMatchingTypedForeground(backgroundOnly, preferredType, requirements, startupShell)) {
			return true
		}

		val notification = if (backgroundOnly) {
			TrackerNotificationManager.getBackgroundCollectionForegroundNotification(this, combinedSources)
		} else {
			foregroundNotification(requirements, notificationIsUserInitiatedHint, startupShell)
		}
		for (type in candidates) {
			if (tryStartForeground(notification, type)) {
				return recordForegroundPromotion(type, requirements, startupShell)
			}
		}
		onForegroundStartFailed(stopServiceOnFailure)
		return false
	}

	private fun hasMatchingTypedForeground(
		backgroundOnly: Boolean,
		preferredType: Int?,
		requirements: ForegroundServiceRequirements,
		startupShell: Boolean,
	): Boolean = !backgroundOnly && foregroundStarted && activeForegroundServiceType == preferredType &&
		activeForegroundRequirements == requirements && foregroundIsStartupShell == startupShell

	private fun recordForegroundPromotion(
		type: Int?,
		requirements: ForegroundServiceRequirements,
		startupShell: Boolean,
	): Boolean {
		foregroundStarted = true
		foregroundIsStartupShell = startupShell
		activeForegroundServiceType = type
		activeForegroundRequirements = requirements
		onForegroundServiceTypeChanged()
		return true
	}

	private fun foregroundNotification(
		requirements: ForegroundServiceRequirements,
		isUserInitiatedHint: Boolean?,
		startupShell: Boolean,
	): Notification = if (startupShell) {
		TrackerNotificationManager.getStartupRecoveryForegroundNotification(
			context = this,
			isUserInitiatedSession = isUserInitiatedHint ?: false,
		)
	} else {
		TrackerNotificationManager.getForegroundNotification(
			context = this,
			usesLocation = requirements.requiresLocation,
			isUserInitiatedSession = sessionInfo?.isInitiatedByUser ?: isUserInitiatedHint,
		)
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
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.TRACKING_START_FAILED,
				TrackingDiagnosticFailureReason.INITIALIZATION_FAILURE,
			)
			TrackerNotificationManager.postStartFailedNotification(this)
			requestGracefulStop(reason = TrackingStopCandidateReason.PERMISSION_UNAVAILABLE)
		}
	}

	private suspend fun processCycleUpdate(cycle: TrackingCycle) {
		val wakeLockStartedAtNanos = Time.elapsedRealtimeNanos
		wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
		try {
			orchestrator.onCycleUpdate(this@TrackerService, cycle)
		} finally {
			if (wakeLock.isHeld) wakeLock.release()
			coordinatorTelemetry.recordTrackingFrame(
				wakeLockNanos = Time.elapsedRealtimeNanos - wakeLockStartedAtNanos,
			)
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
			TrackerDiagnosticLog.warn(
				TrackerDiagnosticWarningCode.TRACKING_SHUTDOWN_DEGRADED,
			)
		}
		return cancelled
	}

	private fun requestGracefulStop(
		startId: Int? = null,
		reason: TrackingStopCandidateReason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		externalCommand: TrackingStopCommand? = null,
	) {
		synchronized(preparedStartOwnershipMonitor) {
			if (externalCommand != null &&
				!trackingLifecycleCommandAuthority.isStopActionable(externalCommand)
			) {
				return
			}
			// A durable End may still be queued for main-thread delivery when an internal
			// reconciliation fails. Preserve that already-issued user's cutoff and ownership.
			val effectiveCommand = externalCommand ?: trackingLifecycleCommandAuthority.pendingStopForTeardown(
				preparedStartRuntime.applyStarted,
			)
			val effectiveReason = effectiveCommand?.reason ?: reason
			if (gracefulStopRequested) {
				if (effectiveCommand != null) {
					handleRepeatedExternalStopRequest(startId, effectiveReason, effectiveCommand)
				}
				return
			}
			TrackerDiagnosticLog.trackingStopRequested()
			gracefulStopRequested = true
			finishStartingSessionForStop(effectiveCommand)
			val stopGeneration = ++stopRequestGeneration
			stopReason = effectiveReason
			activeExternalStop = effectiveCommand?.let { command ->
				ActiveExternalStop(
					command = command,
					cutoff = command.toLiveSourceSessionStopCutoff(
						currentBootId = bootClockDomainProvider.current(),
						receivedElapsedRealtimeNanos = Time.elapsedRealtimeNanos,
					),
				)
			}
			descriptorObservationJob?.cancel()
			descriptorObservationJob = null
			collectionMotionObservationJob?.cancel()
			collectionMotionObservationJob = null
			// Initialization can still be suspended on preferences, foreground setup, or component
			// construction.  Cancel it before persisting the candidate so it cannot later overwrite the
			// terminal descriptor with the provisional ACTIVE record.
			initializationJob?.cancel()
			val stopCandidate = activeSessionDescriptor?.proposeStop(
				reason = effectiveReason,
				changedAtEpochMs = activeExternalStop?.cutoff?.wallTimeMs ?: System.currentTimeMillis(),
			)
			activeSessionDescriptor = stopCandidate
			liveTeardownJob = launch {
				initializationJob?.join()
				// Keep the stop candidate durable until teardown completes. An unexpected kill in this
				// interval must not be interpreted as a user-session crash eligible for restart.
				if (!completeDurableLiveStop(stopGeneration, stopCandidate)) {
					return@launch
				}
				durableStopReadyGeneration = stopGeneration
				releaseStoppedService(startId)
			}
		}
	}

	private fun finishStartingSessionForStop(command: TrackingStopCommand?) {
		(command?.generation ?: preparedStartRuntime.command?.generation)?.let {
			controller.updateSessionStart(it, starting = false)
		}
	}

	private suspend fun completeDurableLiveStop(
		stopGeneration: Long,
		stopCandidate: ActiveTrackingSessionDescriptor?,
	): Boolean = completeDurableStopBeforeForegroundRelease(
		isCurrent = { gracefulStopRequested && stopRequestGeneration == stopGeneration },
		persistStopCandidate = {
			stopCandidate == null || saveActiveSession(stopCandidate, requireLifecycleState = true)
		},
		retireSources = { retireStartedSourcesAtStop() },
		onFailure = { attempt, _ ->
			if (attempt == 1L || attempt % PERSISTENT_TEARDOWN_LOG_EVERY_ATTEMPTS == 0L) {
				TrackerDiagnosticLog.trackingProviderTeardownFailed(
					TrackingDiagnosticFailureReason.PROVIDER_FAILURE,
					attempt,
				)
			}
		},
	)

	private suspend fun retireStartedSourcesAtStop(): Boolean {
		if (!preparedStartRuntime.applyStarted) return true
		val outcome = sourceSession.stop(
			reason = stopReason.name,
			preserveLogicalSession = false,
			factualCutoff = activeExternalStop?.cutoff,
		)
		outcome.requestCapturedActivityProjectionOnSettlement(
			sourcePipelineRecovery::requestActivityCapturedFactDrain,
		)
		return outcome == SourceSessionStopOutcome.Stopped || outcome == SourceSessionStopOutcome.NotActive
	}

	private suspend fun releaseStoppedService(startId: Int?) {
		val stopAfterStartId = startId ?: latestDeliveredStartId.takeIf { it > 0 }
		if (backgroundLocationHost.hasActiveSources && sessionInfo != null) {
			finishLiveSessionKeepingBackground()
			return
		}
		val currentExternalStop = activeExternalStop?.command
		if (currentExternalStop == null) {
			if (stopAfterStartId == null) stopSelf() else stopSelfResult(stopAfterStartId)
		} else {
			completeExternalStopDelivery(currentExternalStop, stopAfterStartId)
		}
	}

	private fun handleRepeatedExternalStopRequest(
		startId: Int?,
		reason: TrackingStopCandidateReason,
		externalCommand: TrackingStopCommand,
	) {
		val currentExternalStop = activeExternalStop
		if (selectLatestExternalStopCommand(currentExternalStop?.command, externalCommand) !=
			externalCommand
		) return
		activeExternalStop = ActiveExternalStop(
			command = externalCommand,
			cutoff = selectEarliestExternalStopCutoff(
				currentExternalStop?.cutoff,
				externalCommand.toLiveSourceSessionStopCutoff(
					currentBootId = bootClockDomainProvider.current(),
					receivedElapsedRealtimeNanos = Time.elapsedRealtimeNanos,
				),
			),
		)
		stopReason = reason
		val pendingStop = liveTeardownJob
		val stopGeneration = stopRequestGeneration
		launch {
			pendingStop?.join()
			if (durableStopReadyGeneration == stopGeneration) {
				completeExternalStopDelivery(
					externalCommand,
					startId ?: latestDeliveredStartId.takeIf { it > 0 },
				)
			}
		}
	}

	private suspend fun completeExternalStopDelivery(
		command: TrackingStopCommand,
		stopAfterStartId: Int?,
	) {
		if (durableStopReadyGeneration != stopRequestGeneration) return
		if (!trackingLifecycleCommandAuthority.isStopActionable(command)) {
			withdrawSupersededExternalStop(command)
			return
		}
		// A repeated End command updates the same cutoff while live teardown retains background.
		if (backgroundLocationHost.hasActiveSources && liveTeardownJob?.isActive == true) return
		val stopped = if (stopAfterStartId == null) {
			stopSelf()
			true
		} else {
			stopSelfResult(stopAfterStartId)
		}
		if (!stopped) withdrawSupersededExternalStop(command)
	}

	private fun discardStartRequest(startId: Int): Int {
		if (!backgroundLocationHost.hasActiveSources) stopSelfResult(startId)
		return START_NOT_STICKY
	}

	private fun handleRejectedStartCommand(command: TrackingStartCommand, startId: Int) {
		when (val disposition = trackingLifecycleCommandAuthority.resolveStart(command)) {
			TrackingStartCommandDisposition.Allowed -> requestGracefulStop(
				startId,
				TrackingStopCandidateReason.INITIALIZATION_FAILURE,
			)
			TrackingStartCommandDisposition.Stale -> discardStartRequest(startId)
			is TrackingStartCommandDisposition.BlockedByStop -> requestGracefulStop(
				startId = startId,
				reason = disposition.stop.reason,
				externalCommand = disposition.stop,
			)
		}
	}

	private fun supersedeOlderExternalStop(command: TrackingStartCommand) {
		synchronized(preparedStartOwnershipMonitor) {
			val stop = activeExternalStop?.command ?: return
			if (command.generation <= stop.generation ||
				trackingLifecycleCommandAuthority.isStopCurrent(stop)
			) return
			gracefulStopRequested = false
			stopRequestGeneration++
			stopReason = TrackingStopCandidateReason.UNKNOWN
			activeExternalStop = null
			startSingleFlight.set(false)
			val restored = activeSessionDescriptor?.withdrawStopCandidate(System.currentTimeMillis())
			activeSessionDescriptor = restored
			if (restored != null) launch { saveActiveSession(restored) }
		}
	}

	private suspend fun withdrawSupersededExternalStop(command: TrackingStopCommand) {
		val restored = synchronized(preparedStartOwnershipMonitor) {
			if (activeExternalStop?.command != command) return
			if (trackingLifecycleCommandAuthority.isStopCurrent(command)) return
			gracefulStopRequested = false
			stopRequestGeneration++
			stopReason = TrackingStopCandidateReason.UNKNOWN
			activeExternalStop = null
			startSingleFlight.set(false)
			val replacement = activeSessionDescriptor?.withdrawStopCandidate(System.currentTimeMillis())
			activeSessionDescriptor = replacement
			replacement
		}
		if (restored != null) saveActiveSession(restored)
	}

	private suspend fun clearCompletedStopCandidate() {
		val stopCandidate = activeSessionDescriptor
			?.takeIf { it.lifecycleState == LogicalTrackingLifecycleState.STOP_CANDIDATE }
			?: run {
				markLatestExternalStopHandled()
				return
			}
		val result = activeTrackingSessionStore.clearIfCurrent(stopCandidate)
		if (result is ActiveTrackingSessionStoreResult.Failure) {
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.TRACKING_SESSION_STORE_FAILED,
				TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
			)
			return
		}
		// Do not overwrite a newer service run's descriptor if it won the atomic comparison.
		if ((result as ActiveTrackingSessionStoreResult.Success).descriptor == null) {
			val cleared = synchronized(preparedStartOwnershipMonitor) {
				if (activeSessionDescriptor != stopCandidate) false else { activeSessionDescriptor = null; true }
			}
			if (cleared) markLatestExternalStopHandled()
		}
	}

	private suspend fun markLatestExternalStopHandled() {
		while (true) {
			val command = activeExternalStop?.command ?: return
			if (trackingLifecycleCommandAuthority.markStopHandled(command)) return
			if (activeExternalStop?.command == command) return
		}
	}

	private suspend fun saveActiveSession(
		descriptor: ActiveTrackingSessionDescriptor,
		requireLifecycleState: Boolean = false,
	): Boolean {
		val expected = synchronized(preparedStartOwnershipMonitor) {
			val current = activeSessionDescriptor
			if (current == null || current.logicalTrackingId != descriptor.logicalTrackingId ||
				current.serviceRunId != descriptor.serviceRunId
			) {
				return false
			}
			preparedStartRuntime to current
		}
		return when (val result = activeTrackingSessionStore.mergeServiceDescriptor(descriptor)) {
			is ActiveTrackingSessionStoreResult.Failure -> {
				TrackerDiagnosticLog.failure(
					TrackerDiagnosticFailureCode.TRACKING_SESSION_STORE_FAILED,
					TrackingDiagnosticFailureReason.STORAGE_UNAVAILABLE,
				)
				false
			}
			is ActiveTrackingSessionStoreResult.Success -> publishSavedServiceDescriptor(
				expected,
				result.descriptor,
				descriptor,
				requireLifecycleState,
			)
		}
	}

	private fun publishSavedServiceDescriptor(
		expected: Pair<PreparedStartRuntimeState, ActiveTrackingSessionDescriptor>,
		stored: ActiveTrackingSessionDescriptor?,
		requested: ActiveTrackingSessionDescriptor,
		requireLifecycleState: Boolean,
	): Boolean {
		val persisted = stored?.takeIf { it.matchesSavedServiceDescriptor(requested, requireLifecycleState) }
		if (persisted == null) {
			TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.TRACKING_SESSION_STORE_FAILED,
				TrackingDiagnosticFailureReason.INTERNAL_INVARIANT,
			)
			return false
		}
		return synchronized(preparedStartOwnershipMonitor) {
			if (preparedStartRuntime.token != expected.first.token ||
				preparedStartRuntime.command != expected.first.command ||
				activeSessionDescriptor != expected.second
			) {
				false
			} else {
				activeSessionDescriptor = persisted
				true
			}
		}
	}

	private fun ActiveTrackingSessionDescriptor.matchesSavedServiceDescriptor(
		requested: ActiveTrackingSessionDescriptor,
		requireLifecycleState: Boolean,
	): Boolean = logicalTrackingId == requested.logicalTrackingId &&
		serviceRunId == requested.serviceRunId &&
		(!requireLifecycleState ||
			(lifecycleState == requested.lifecycleState && lifecycleRevision >= requested.lifecycleRevision))

	override fun onDestroy() {
		preparedStartRuntime.command?.let { controller.updateSessionStart(it.generation, starting = false) }
		pendingBackgroundRetirement = backgroundLocationHost.detachAll(this)
			.firstOrNull { it.source == com.adsamcik.tracker.tracker.api.AmbientTrackingSource.LOCATION }
		backgroundHostObservationJob?.cancel()
		backgroundHostObservationJob = null
		liveTeardownJob?.cancel()
		liveTeardownJob = null
		// Keep STOP delivery owned while provider teardown is in flight. Inactive finalization is
		// released only after the existing teardown gate proves source-session retirement.
		TrackerRuntimeStopDispatcher.beginTeardown(this)
		// Capture references before super.onDestroy() cancels the coroutine scope
		val initializationRef = initializationJob
		val precedingTeardown = synchronized(SERVICE_GENERATION_LOCK) {
			lastTeardownGate
		}
		initializationRef?.cancel()
		initializationJob = null
		sessionRecoveryJob?.cancel()
		sessionRecoveryJob = null
		synchronized(preparedStartOwnershipMonitor) { startSingleFlight.set(false) }
		descriptorObservationJob?.cancel()
		descriptorObservationJob = null
		runtimePermissionObservationJob?.cancel()
		runtimePermissionObservationJob = null
		runtimePermissionReconfigurePending.set(false)
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
		powerSaveObservationJob?.cancel()
		powerSaveObservationJob = null

		// Fire-and-forget cleanup on an independent scope to avoid blocking the main thread.
		// Replacement initialization awaits this deferred, so cleanup keeps retrying until
		// shared singleton processors and hardware producers are actually quiescent.
		val cleanupScope = kotlinx.coroutines.CoroutineScope(
			dispatchers.default + kotlinx.coroutines.SupervisorJob()
		)
		val cleanupGate = TrackingTeardownGate(
			recovery = { performTeardown(context) },
			runtimeStopOwner = this,
		)
		lateinit var cleanupJob: Deferred<Unit>
		cleanupJob = cleanupScope.async(start = CoroutineStart.LAZY) {
			var shutdownCompleted = false
			try {
				precedingTeardown?.job?.join()
				TRACKING_LIFECYCLE_BARRIER.runAfter(initializationRef) {
					recoverIncompleteTeardown(precedingTeardown)
					awaitTeardownRecovery(cleanupGate)
					clearCompletedStopCandidate()
					cleanupGate.cleanupComplete.set(true)
					TrackerRuntimeStopDispatcher.completeTeardown(this@TrackerService)
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

	private suspend fun awaitTeardownRecovery(gate: TrackingTeardownGate) {
		retryTrackingShutdownUntilSuccess(
			retryDelayMillis = PERSISTENT_TEARDOWN_INITIAL_RETRY_DELAY_MILLIS,
			maxRetryDelayMillis = PERSISTENT_TEARDOWN_MAX_RETRY_DELAY_MILLIS,
			onFailure = { attempt, failure ->
				if (attempt == 1L || attempt % PERSISTENT_TEARDOWN_LOG_EVERY_ATTEMPTS == 0L) {
					TrackerDiagnosticLog.trackingProviderTeardownFailed(
						TrackingDiagnosticFailureReason.PROVIDER_FAILURE,
						attempt,
					)
				}
			},
		) {
			gate.recovery()
		}
	}

	private suspend fun recoverIncompleteTeardown(gate: TrackingTeardownGate?) {
		if (gate == null || gate.cleanupComplete.get()) return

		awaitTeardownRecovery(gate)
		gate.cleanupComplete.set(true)
		TrackerRuntimeStopDispatcher.completeTeardown(gate.runtimeStopOwner)
		synchronized(SERVICE_GENERATION_LOCK) {
			if (lastTeardownGate === gate) {
				lastTeardownGate = null
			}
		}
	}

	private suspend fun performTeardown(context: Context) {
		pendingBackgroundRetirement?.let { identity ->
			if (!ambientLocationPurposeOwner.get().retire(AmbientReconciliationLease(identity))) {
				throw TrackingShutdownRetryException("BACKGROUND_LOCATION_RETIREMENT_PENDING")
			}
			pendingBackgroundRetirement = null
		}
		if (!backgroundLocationHost.hasActiveSources) {
			locationMotionAssistanceOwner.reconcile(null)
			collectionMotionController.stopScope(locationScopeId)
			fallbackMotionScopeId = null
		}
		val serviceRunId = activeSessionDescriptor?.serviceRunId
		val preparedRuntime = preparedStartRuntime
		if (!preparedRuntime.applied && !preparedRuntime.platformOwned) {
			val token = preparedRuntime.token
			val command = preparedRuntime.command
			if (token != null && command != null) {
				trackingStartRequestCoordinator.compensate(
					token,
					command,
					"PREPARED_START_SERVICE_DESTROYED_BEFORE_APPLY",
				)
			}
		}
		if (preparedRuntime.applyStarted) {
			retryTrackingShutdown {
				val outcome = sourceSession.stop(
					reason = stopReason.name,
					preserveLogicalSession = !gracefulStopRequested,
					factualCutoff = activeExternalStop?.cutoff,
				)
				outcome.requestCapturedActivityProjectionOnSettlement(
					sourcePipelineRecovery::requestActivityCapturedFactDrain,
				)
				when (outcome) {
					SourceSessionStopOutcome.Stopped,
					SourceSessionStopOutcome.NotActive -> Unit
					is SourceSessionStopOutcome.Retryable ->
						throw TrackingShutdownRetryException(outcome.code.name)
				}
			}
			if (!backgroundLocationHost.hasActiveSources) {
				locationMotionAssistanceOwner.reconcile(null)
				collectionMotionController.stopScope(locationScopeId)
				serviceRunId?.let(collectionMotionController::stopSession)
			}
			if (trackingStartupGate.isReady) {
				sourcePipelineRecovery.drainCommittedWork()
			}
		}
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
		var finalShutdownResult = shutdownSequence.shutdownResult
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
			TrackerDiagnosticLog.warn(
				TrackerDiagnosticWarningCode.TRACKING_SHUTDOWN_DEGRADED,
			)
			orchestrator.enqueueDailySummaryFallback(context)
			finalShutdownResult = retryTrackingShutdown(
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
		acknowledgePresentationQuiescence(requireNotNull(finalShutdownResult))
		locationLiveWriterRegistry.detach(orchestrator)
		if (preparedRuntime.applied) {
			HistoricalTrajectoryReconstructionWorker.schedule(context)
		}
	}

	private suspend fun acknowledgePresentationQuiescence(shutdownResult: ShutdownResult) {
		val binding = shutdownResult.presentationReceipt?.binding ?: recoveredFailedInitializationBinding() ?: return
		when (
			sessionPresentationLifecycle.acknowledgeQuiescedExact(
				binding = binding,
				acknowledgedAtMs = System.currentTimeMillis(),
			)
		) {
			PresentationQuiescenceResult.ACKNOWLEDGED,
			PresentationQuiescenceResult.ALREADY_ACKNOWLEDGED,
			-> Unit
			PresentationQuiescenceResult.NOT_TERMINAL,
			-> throw TrackingShutdownRetryException("PRESENTATION_QUIESCENCE_NOT_ACCEPTED")
			PresentationQuiescenceResult.MISSING,
			PresentationQuiescenceResult.OWNERSHIP_MISMATCH,
			-> TrackerDiagnosticLog.failure(
				TrackerDiagnosticFailureCode.TRACKING_PERSISTENCE_WRITE_FAILED,
				TrackingDiagnosticFailureReason.INTERNAL_INVARIANT,
			)
		}
	}

	private suspend fun recoveredFailedInitializationBinding(): SessionPresentationBinding? {
		val descriptor = activeSessionDescriptor
		if (descriptor != null) {
			return sessionPresentationLifecycle.recoveredInitializationBinding(
				descriptor.logicalTrackingId, descriptor.serviceRunId)
		}
		val runtime = preparedStartRuntime
		val token = runtime.token ?: return null
		val command = runtime.command ?: return null
		val run = appDatabase.sourceSessionDao().serviceRunByDeliveryToken(token.value)
			?.takeIf { it.startCommandGeneration == command.generation }
		return run?.let {
			sessionPresentationLifecycle.recoveredInitializationBinding(it.logicalTrackingId, it.serviceRunId)
		}
	}

	private suspend fun sourcePlanInputs(
		settings: TrackingParamsState,
		demands: List<SourceDemand>,
	): SourceSessionPlanInputs {
		val packageManager = packageManager
		val permissionCapabilities = trackingPermissionCapabilities()
		val foreground = activeForegroundRequirements
		val liveSources = activeSessionDescriptor?.let { descriptor ->
			sessionRolloutState?.let { rollout ->
				resolveActiveSessionOwnership(rollout, settings, descriptor).enabledEventSources
			}
		}.orEmpty()
		val startOrigin = sessionStartOrigin ?: SessionStartOrigin.POLICY_RECONCILIATION
		val constraints = currentSourceConstraints(packageManager, permissionCapabilities, foreground, startOrigin)
		return SourceSessionPlanInputs(
			settings = settings,
			environment = SourcePlanEnvironment(
				locationBackend = TrackerTimerManager.getSelectedLocationBackend(this),
				preciseLocationAvailable = permissionCapabilities.hasPreciseLocation,
				subscriptionIds = emptySet(),
				liveSources = liveSources.filterTo(linkedSetOf()) { source ->
					constraints[source]?.acceptsLiveSource() == true
				},
			),
			resolutionContext = PlanResolutionContext(
				constraints = constraints,
				powerSaver = powerManager.isPowerSaveMode,
				batteryPressure = batteryAwarePolicy.currentPressure(),
				doze = powerManager.isDeviceIdleMode,
				severeThermalPressure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
					powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
				motionProfile = collectionMotionController.policy.value.acquisitionProfile,
				locationProfileLevel = settings.locationBalanceLevel,
				locationLiveFocus = SourceKind.LOCATION in liveSources &&
					constraints[SourceKind.LOCATION]?.acceptsLiveSource() == true,
			),
			demands = demands,
			clockDomainId = bootClockDomainProvider.current(),
		)
	}

	private fun prepareForegroundForSourcePlan(
		settings: TrackingParamsState,
		rollout: TrackingRolloutState,
	): Boolean {
		synchronized(preparedStartOwnershipMonitor) {
			val descriptor = activeSessionDescriptor ?: return false
			val ownership = resolveActiveSessionOwnership(rollout, settings, descriptor)
			val accepted = resolveAcceptedForegroundSources(
				requestedSources = ownership.enabledEventSources,
				startOrigin = sessionStartOrigin ?: SessionStartOrigin.POLICY_RECONCILIATION,
			)
			return accepted.isNotEmpty() && ensureForegroundStarted(accepted)
		}
	}

	private fun resolveAcceptedForegroundSources(
		requestedSources: Set<SourceKind>,
		startOrigin: SessionStartOrigin,
	): Set<SourceKind> {
		val packageManager = packageManager
		val locationFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
		val wifiFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
		val cellFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
			(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))
		val permissionCapabilities = trackingPermissionCapabilities()
		return acceptedForegroundSources(
			requestedSources,
			ForegroundSourceCapabilities(
				sdkInt = Build.VERSION.SDK_INT,
				startOrigin = startOrigin,
				hasForegroundLocationPermission = hasLocationPermission,
				hasBackgroundLocationPermission = hasBackgroundLocationPermission,
				locationHardwareAvailable = locationFeature,
				activity = hasActivityPermission && Assist.isPlayServicesAvailable(this),
				steps = hasActivityPermission && hasStepCounterSensor,
				pressure = hasPressureSensor,
				wifi = wifiFeature && permissionCapabilities.hasWifiScan,
				cell = cellFeature && hasCellScanPermission,
			),
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
		private const val PERSISTENT_TEARDOWN_INITIAL_RETRY_DELAY_MILLIS = 1_000L
		private const val PERSISTENT_TEARDOWN_MAX_RETRY_DELAY_MILLIS = 60_000L
		private const val PERSISTENT_TEARDOWN_LOG_EVERY_ATTEMPTS = 10L
	}
}

internal fun shouldStopAfterRuntimePermissionReconfigure(
	outcome: SourceSessionReconfigureOutcome?,
	acceptedCaptureSourceCount: Int? = null,
): Boolean = acceptedCaptureSourceCount == 0 || outcome is SourceSessionReconfigureOutcome.Rejected

internal fun resolveActiveSessionOwnership(
	rollout: TrackingRolloutState,
	settings: TrackingParamsState,
	descriptor: ActiveTrackingSessionDescriptor,
): TrackingSessionOwnership = TrackingSessionOwnership.resolve(
	rollout = rollout,
	settings = settings,
	captureMode = captureModeFor(
		isUserInitiated = descriptor.isUserInitiated,
		isAmbient = descriptor.isAmbient,
	),
)

internal fun validateAutomaticStartAtRuntime(
	automaticStartExpected: Boolean,
	trigger: AutomaticTrackingStartTrigger?,
	currentBootId: String,
	currentElapsedRealtimeNanos: Long,
	currentPolicyRevision: Long?,
	currentAutomationEpoch: Long?,
	hasActivityPermission: Boolean,
): String? = when {
	!automaticStartExpected && trigger != null -> AUTOMATIC_START_TRIGGER_UNEXPECTED
	!automaticStartExpected -> null
	trigger == null -> AUTOMATIC_START_TRIGGER_MISSING
	!hasActivityPermission -> AUTOMATIC_START_PERMISSION_REVOKED
	trigger.bootId != currentBootId -> AUTOMATIC_START_BOOT_STALE
	currentElapsedRealtimeNanos < trigger.receivedElapsedRealtimeNanos ||
		currentElapsedRealtimeNanos > trigger.expiresElapsedRealtimeNanos -> AUTOMATIC_START_TRIGGER_STALE
	currentAutomationEpoch == null || trigger.automationEpoch != currentAutomationEpoch ->
		AUTOMATIC_START_EPOCH_STALE
	currentPolicyRevision == null || trigger.sourcePolicyRevision != currentPolicyRevision ->
		AUTOMATIC_START_EPOCH_STALE
	else -> null
}

internal const val AUTOMATIC_START_TRIGGER_UNEXPECTED = "AUTOMATIC_START_TRIGGER_UNEXPECTED"
internal const val AUTOMATIC_START_TRIGGER_MISSING = "AUTOMATIC_START_TRIGGER_MISSING"
internal const val AUTOMATIC_START_PERMISSION_REVOKED = "AUTOMATIC_START_PERMISSION_REVOKED"
internal const val AUTOMATIC_START_BOOT_STALE = "AUTOMATIC_START_BOOT_STALE"
internal const val AUTOMATIC_START_TRIGGER_STALE = "AUTOMATIC_START_TRIGGER_STALE"
internal const val AUTOMATIC_START_EPOCH_STALE = "AUTOMATIC_START_EPOCH_STALE"

/**
 * Both Android redelivery flags can represent a prior delivery whose durable STARTING/ACTIVE
 * state is no longer known to this process. They therefore use the recovery-safe foreground
 * capability set and the exact-token redelivery resolver before claim.
 */
internal fun isPassivePreparedStartDelivery(flags: Int): Boolean =
	flags and (Service.START_FLAG_REDELIVERY or Service.START_FLAG_RETRY) != 0

/** An internal failure must honor an End already saved before its main-thread delivery. */
internal fun TrackingLifecycleCommandAuthority.pendingStopForTeardown(
	hasAppliedSources: Boolean,
): TrackingStopCommand? = if (hasAppliedSources) {
	latestUnhandledStop()?.takeIf(::isStopActionable)
} else {
	null
}

/** A repeated stop may refresh ownership, but an older delivery can never reclaim it. */
internal fun selectLatestExternalStopCommand(
	active: TrackingStopCommand?,
	incoming: TrackingStopCommand,
): TrackingStopCommand = if (active == null || incoming.generation >= active.generation) {
	incoming
} else {
	active
}

/** Command ownership is independent from the earliest factual end of the affected service run. */
internal fun selectEarliestExternalStopCutoff(
	active: SourceSessionStopCutoff?,
	incoming: SourceSessionStopCutoff,
): SourceSessionStopCutoff = when {
	active == null -> incoming
	active.clockDomainId == incoming.clockDomainId &&
		incoming.elapsedRealtimeNanos < active.elapsedRealtimeNanos -> incoming
	active.clockDomainId != incoming.clockDomainId && incoming.wallTimeMs < active.wallTimeMs -> incoming
	else -> active
}

/** Reuses a monotonic STOP timestamp only inside the boot where it was captured. */
internal fun TrackingStopCommand.toLiveSourceSessionStopCutoff(
	currentBootId: String,
	receivedElapsedRealtimeNanos: Long,
): SourceSessionStopCutoff {
	require(currentBootId.isNotBlank())
	require(receivedElapsedRealtimeNanos >= 0L)
	return SourceSessionStopCutoff(
		wallTimeMs = requestedAtEpochMs,
		elapsedRealtimeNanos = requestedElapsedRealtimeNanos
			?.takeIf { requestedBootId == currentBootId }
			?: receivedElapsedRealtimeNanos,
		clockDomainId = currentBootId,
	)
}

private data class ActiveExternalStop(
	val command: TrackingStopCommand,
	val cutoff: SourceSessionStopCutoff,
)

private class TrackingTeardownGate(
	val recovery: suspend () -> Unit,
	val runtimeStopOwner: Any,
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

internal data class ForegroundSourceCapabilities(
	val sdkInt: Int,
	val startOrigin: SessionStartOrigin,
	val hasForegroundLocationPermission: Boolean,
	val hasBackgroundLocationPermission: Boolean,
	val locationHardwareAvailable: Boolean,
	val activity: Boolean,
	val steps: Boolean,
	val pressure: Boolean,
	val wifi: Boolean,
	val cell: Boolean,
)

internal data class PreparedStartRuntimeState(
	val token: PreparedTrackingStartToken? = null,
	val command: TrackingStartCommand? = null,
	val applyStarted: Boolean = false,
	val applied: Boolean = false,
	val platformOwned: Boolean = false,
	val pendingPredecessor: RebasedPredecessorDelivery? = null,
) {
	fun matches(deliveredToken: PreparedTrackingStartToken, deliveredCommand: TrackingStartCommand) =
		token == deliveredToken && command?.generation == deliveredCommand.generation

	fun consumeRebasedPredecessor(
		deliveredToken: PreparedTrackingStartToken,
		deliveredCommand: TrackingStartCommand,
	): Int? = pendingPredecessor?.consume(deliveredToken, deliveredCommand)
}

internal fun Intent?.isExactPreparedTrackingStartAction(): Boolean =
	this?.action == TrackerServiceContract.ACTION_PREPARED_START

private data class PreparedForegroundHint(
	val sources: Set<SourceKind>,
	val isUserInitiated: Boolean,
)

internal fun shouldStopServiceForDiscardedPreparedStart(
	activeRuntimePresent: Boolean,
	startFlightInProgress: Boolean,
	preparedRuntimePresent: Boolean,
): Boolean = !activeRuntimePresent && !startFlightInProgress && !preparedRuntimePresent

internal class RebasedPredecessorDelivery(
	val replacementToken: PreparedTrackingStartToken,
	val replacementCommandGeneration: Long,
	val predecessorStartId: Int,
	private val consumed: AtomicBoolean = AtomicBoolean(false),
) {
	init {
		require(replacementCommandGeneration > 0L)
		require(predecessorStartId > 0)
	}

	fun consume(
		deliveredToken: PreparedTrackingStartToken,
		deliveredCommand: TrackingStartCommand,
	): Int? = predecessorStartId.takeIf {
		replacementToken == deliveredToken &&
			replacementCommandGeneration == deliveredCommand.generation &&
			consumed.compareAndSet(false, true)
	}
}

internal data class RebasedStartDeliveryResolution(
	val predecessorStartId: Int?,
	val commandDisposition: TrackingStartCommandDisposition,
)

/** Factual replacement delivery is consumed before stale/STOP policy can reject that delivery. */
internal fun resolveRebasedStartDelivery(
	runtime: PreparedStartRuntimeState,
	deliveredToken: PreparedTrackingStartToken,
	deliveredCommand: TrackingStartCommand,
	resolveCommand: () -> TrackingStartCommandDisposition,
): RebasedStartDeliveryResolution {
	val predecessorStartId = runtime.consumeRebasedPredecessor(deliveredToken, deliveredCommand)
	return RebasedStartDeliveryResolution(predecessorStartId, resolveCommand())
}

internal sealed interface AndroidRedeliveryRebaseResult {
	data class Rebased(val enqueueAcknowledged: Boolean) : AndroidRedeliveryRebaseResult
	data object EnqueueFailed : AndroidRedeliveryRebaseResult
	data object Stale : AndroidRedeliveryRebaseResult
	data class BlockedByStop(val stop: TrackingStopCommand) : AndroidRedeliveryRebaseResult
}

/**
 * Enqueues the distinct recovery run after rechecking STOP ordering. Retiring the predecessor is
 * intentionally separate: only the matching replacement `onStartCommand` proves Android assigned
 * and delivered the newer start ID, at which point [RebasedPredecessorDelivery] consumes it once.
 */
internal suspend fun rebasePreparedAndroidStart(
	command: TrackingStartCommand,
	validateAndEnqueue: suspend (
		TrackingStartCommand,
		suspend () -> Boolean,
	) -> LockedTrackingStartResult<Boolean>,
	platformEnqueue: suspend () -> Boolean,
	markEnqueued: suspend () -> Boolean,
): AndroidRedeliveryRebaseResult = when (
	val enqueue = validateAndEnqueue(command, platformEnqueue)
) {
	is LockedTrackingStartResult.Executed -> {
		if (!enqueue.value) {
			AndroidRedeliveryRebaseResult.EnqueueFailed
		} else {
			AndroidRedeliveryRebaseResult.Rebased(markEnqueued())
		}
	}
	LockedTrackingStartResult.Stale -> AndroidRedeliveryRebaseResult.Stale
	is LockedTrackingStartResult.BlockedByStop ->
		AndroidRedeliveryRebaseResult.BlockedByStop(enqueue.stop)
}

/** One end-to-end budget from descriptor read through the first exact foreground promotion. */
internal const val PRE_FOREGROUND_START_BUDGET_MILLIS = 3_000L

/** Maximum honest providerless foreground wait for cold startup recovery. */
internal const val STARTUP_FOREGROUND_SHELL_MAX_MILLIS = 30_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

private fun Long.saturatedAdd(other: Long): Long =
	if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

/** Sources requested by the current authoritative settings projection, before capability checks. */
internal fun configuredForegroundSources(params: TrackingParamsState): Set<SourceKind> = buildSet {
	if (params.locationEnabled) add(SourceKind.LOCATION)
	if (params.activityEnabled) add(SourceKind.ACTIVITY)
	if (params.stepsEnabled) add(SourceKind.STEPS)
	if (params.barometerEnabled) add(SourceKind.PRESSURE)
	if (params.wifiEnabled) add(SourceKind.WIFI)
	if (params.cellEnabled) add(SourceKind.CELL)
}

internal fun sourceKindsFromMask(mask: Long): Set<SourceKind>? {
	if (mask < 0L) return null
	val validMask = SourceKind.entries.fold(0L) { result, source ->
		result or (1L shl (source.stableCode - 1))
	}
	if (mask and validMask.inv() != 0L) return null
	return SourceKind.entries.filterTo(mutableSetOf()) { source ->
		mask and (1L shl (source.stableCode - 1)) != 0L
	}
}

internal fun sourceMask(sources: Set<SourceKind>): Long = sources.fold(0L) { mask, source ->
	mask or (1L shl (source.stableCode - 1))
}

/** Terminal source authority is durable only after the exact stop transition commits. */
internal fun SourceSessionStopOutcome.requestCapturedActivityProjectionOnSettlement(
	requestDrain: () -> Unit,
) {
	if (this == SourceSessionStopOutcome.Stopped) requestDrain()
}

/** Rollout containment degrades only the named sources; it never blocks a reachable sibling. */
internal fun rolloutReachableCaptureSources(
	requestedSources: Set<SourceKind>,
	rollout: TrackingRolloutState,
): Set<SourceKind> = requestedSources.filterTo(linkedSetOf(), rollout::isAcquisitionReachable)

/** Null means there is no accepted demand and therefore no legal foreground promotion. */
internal fun foregroundServiceTypeMask(
	sdkInt: Int,
	acceptedSources: Set<SourceKind>,
): Long? {
	val candidates = foregroundServiceTypeCandidates(sdkInt, acceptedSources)
	if (candidates.isEmpty()) return null
	return candidates.single()?.toLong() ?: 0L
}

internal fun automaticForegroundEnvelopeMatches(
	trigger: AutomaticTrackingStartTrigger,
	requestedSources: Set<SourceKind>,
	acceptedSources: Set<SourceKind>,
	sdkInt: Int,
): Boolean = requestedSources.isNotEmpty() && acceptedSources.isNotEmpty() &&
	trigger.requestedCaptureSourceMask == sourceMask(requestedSources) &&
	trigger.intendedCaptureSourceMask == sourceMask(acceptedSources) &&
	trigger.intendedForegroundServiceTypeMask ==
		foregroundServiceTypeMask(sdkInt, acceptedSources)

internal fun acceptedForegroundSources(
	requestedSources: Set<SourceKind>,
	capabilities: ForegroundSourceCapabilities,
): Set<SourceKind> = requestedSources.filterTo(mutableSetOf()) { source ->
	when (source) {
		SourceKind.LOCATION -> capabilities.locationHardwareAvailable &&
			isLocationSourceLegalForStartOrigin(
				sdkInt = capabilities.sdkInt,
				startOrigin = capabilities.startOrigin,
				hasForegroundLocationPermission = capabilities.hasForegroundLocationPermission,
				hasBackgroundLocationPermission = capabilities.hasBackgroundLocationPermission,
			)
		SourceKind.ACTIVITY -> capabilities.activity
		SourceKind.STEPS -> capabilities.steps
		SourceKind.PRESSURE -> capabilities.pressure
		SourceKind.WIFI -> capabilities.wifi &&
			isPreciseLocationProtectedSignalSourceLegalForStartOrigin(
				sdkInt = capabilities.sdkInt,
				startOrigin = capabilities.startOrigin,
				hasPreciseLocationPermission = capabilities.hasForegroundLocationPermission,
				hasBackgroundLocationPermission = capabilities.hasBackgroundLocationPermission,
			)
		SourceKind.CELL -> capabilities.cell &&
			isPreciseLocationProtectedSignalSourceLegalForStartOrigin(
				sdkInt = capabilities.sdkInt,
				startOrigin = capabilities.startOrigin,
				hasPreciseLocationPermission = capabilities.hasForegroundLocationPermission,
				hasBackgroundLocationPermission = capabilities.hasBackgroundLocationPermission,
			)
	}
}

/**
 * Pure logic: the exact foreground-service declaration required by active tracking sources.
 *
 * Location and the location-protected Wi-Fi/cell scan APIs map to `location`, Activity/Steps to
 * `health`, and Pressure/Wi-Fi/Cell to `specialUse`; mixed accepted source sets use the exact
 * union. `specialUse` does not widen while-in-use location authorization. An empty accepted set is
 * not a promotable service demand. Android 10 introduced the runtime location service type.
 */
@SuppressLint("InlinedApi")
internal fun foregroundServiceTypeCandidates(
	sdkInt: Int,
	acceptedSources: Set<SourceKind>,
): List<Int?> {
	if (acceptedSources.isEmpty()) return emptyList()
	if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		if (sdkInt < Build.VERSION_CODES.Q) return listOf(null)
		return if (acceptedSources.any {
			it == SourceKind.LOCATION || it == SourceKind.WIFI || it == SourceKind.CELL
		}) {
			listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
		} else {
			// The two-argument call inherits every manifest-declared type, including Location.
			// Android 10-13 accept an explicit NONE type for non-location work; Android 14+
			// rejects NONE for modern targets and is handled by the exact typed union below.
			listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
		}
	}
	var union = 0
	if (acceptedSources.any {
		it == SourceKind.LOCATION || it == SourceKind.WIFI || it == SourceKind.CELL
	}) {
		union = union or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
	}
	if (acceptedSources.any { it == SourceKind.ACTIVITY || it == SourceKind.STEPS }) {
		union = union or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
	}
	if (acceptedSources.any {
			it == SourceKind.PRESSURE || it == SourceKind.WIFI || it == SourceKind.CELL
		}) {
		union = union or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
	}
	return listOf(union)
}

private data class ForegroundServiceRequirements(
	val acceptedSources: Set<SourceKind>,
) {
	val requiresLocation: Boolean get() = acceptedSources.any {
		it == SourceKind.LOCATION || it == SourceKind.WIFI || it == SourceKind.CELL
	}
	fun accepts(source: SourceKind): Boolean = source in acceptedSources
}

internal fun isLocationSourceLegalForStartOrigin(
	sdkInt: Int,
	startOrigin: SessionStartOrigin,
	hasForegroundLocationPermission: Boolean,
	hasBackgroundLocationPermission: Boolean,
): Boolean {
	if (!hasForegroundLocationPermission) return false
	return when (startOrigin) {
		SessionStartOrigin.MANUAL_FOREGROUND_START -> true
		SessionStartOrigin.AUTOMATIC_BACKGROUND_START ->
			sdkInt < Build.VERSION_CODES.Q || hasBackgroundLocationPermission
		SessionStartOrigin.RECOVERY,
		SessionStartOrigin.POLICY_RECONCILIATION,
		-> sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || hasBackgroundLocationPermission
	}
}

/**
 * Wi-Fi scan and cell-info APIs are precise-location-protected. A `specialUse` foreground service
 * does not widen location authorization, so a non-user delivery may collect them on Android 10+
 * only when background location was already granted.
 */
internal fun isPreciseLocationProtectedSignalSourceLegalForStartOrigin(
	sdkInt: Int,
	startOrigin: SessionStartOrigin,
	hasPreciseLocationPermission: Boolean,
	hasBackgroundLocationPermission: Boolean,
): Boolean {
	if (!hasPreciseLocationPermission) return false
	return startOrigin == SessionStartOrigin.MANUAL_FOREGROUND_START ||
		sdkInt < Build.VERSION_CODES.Q ||
		hasBackgroundLocationPermission
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

/** Capture authority cannot borrow a hosted source; the physical host still promotes the full union. */
internal fun foregroundPromotionMatches(
	sdkInt: Int,
	captureSources: Set<SourceKind>,
	hostedSources: Set<SourceKind>,
	desiredCaptureMask: Long,
	appliedSources: Set<SourceKind>,
	appliedMask: Long,
): Boolean {
	if (captureSources.isEmpty()) return false
	val captureMask = foregroundServiceTypeMask(sdkInt, captureSources) ?: return false
	val union = captureSources + hostedSources
	val unionMask = foregroundServiceTypeMask(sdkInt, union) ?: return false
	return desiredCaptureMask == captureMask && appliedSources == union && appliedMask == unionMask
}
