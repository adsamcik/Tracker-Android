package com.adsamcik.tracker.tracker.service

import dev.tracebox.Tracebox
import android.annotation.SuppressLint
import android.Manifest
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
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.assist.Assist
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasBackgroundLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasCellScanPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.extension.hasPressureSensor
import com.adsamcik.tracker.shared.base.extension.hasStepCounterSensor
import com.adsamcik.tracker.shared.base.extension.hasWifiScanPermission
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
import com.adsamcik.tracker.tracker.api.PreparedTrackingStartToken
import com.adsamcik.tracker.tracker.api.TrackingStartPreparationResult
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.notification.TrackerNotificationChannels
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.permission.RuntimePermissionReconciler
import com.adsamcik.tracker.tracker.permission.RuntimePermissionSnapshot
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
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SourceConstraint
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionPlanInputs
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionReconfigureOutcome
import com.adsamcik.tracker.tracker.source.coordinator.SourceSessionStopCutoff
import com.adsamcik.tracker.tracker.source.coordinator.TrackerServiceSourceSession
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingSessionOwnership
import com.adsamcik.tracker.tracker.source.coordinator.captureModeFor
import com.adsamcik.tracker.tracker.source.control.CollectionMotionController
import com.adsamcik.tracker.tracker.source.control.LocationCollectionStrategy
import com.adsamcik.tracker.tracker.source.control.acquisitionProfile
import com.adsamcik.tracker.tracker.source.model.SourceDemand
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
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
import kotlinx.coroutines.flow.drop
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
	lateinit var batteryAwarePolicy: BatteryAwarePolicy

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

	private lateinit var orchestrator: TrackingOrchestrator

	private var lockObservationJob: Job? = null
	private var initializationJob: Job? = null
	private var batteryObservationJob: Job? = null
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
	private var latestDeliveredStartId = 0
	@Volatile private var activeExternalStop: ActiveExternalStop? = null
	@Volatile private var stopReason: TrackingStopCandidateReason = TrackingStopCandidateReason.UNKNOWN
	private var coordinatorMetricBaseline: com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics? = null
	@Volatile private var sessionRolloutState: TrackingRolloutState? = null
	@Volatile private var sessionStartOrigin: SessionStartOrigin? = null
	@Volatile private var foregroundStarted = false
	@Volatile private var foregroundIsStartupShell = false
	@Volatile private var preparedStartRuntime = PreparedStartRuntimeState()
	private val startSingleFlight = AtomicBoolean(false)
	private var activeForegroundServiceType: Int? = null
	private var activeForegroundRequirements: ForegroundServiceRequirements? = null
	private val runtimePermissionReconfigurePending = AtomicBoolean(false)
	override fun onCreate() {
		super.onCreate()
		synchronized(SERVICE_GENERATION_LOCK) {
			serviceGeneration = SERVICE_GENERATION_COUNTER.incrementAndGet()
			activeServiceGeneration = serviceGeneration
		}
		TrackerRuntimeStopDispatcher.register(this) { command ->
			check(Looper.myLooper() == Looper.getMainLooper()) {
				"Tracker stop ownership must be acknowledged on the main thread"
			}
			if (!trackingLifecycleCommandAuthority.isStopActionable(command)) {
				false
			} else if (!preparedStartRuntime.applyStarted) {
				// A providerless/claimed-only shell does not own the durable Room lifecycle. Stop the
				// Android shell locally and let the inactive handler finalize the exact stored run.
				requestGracefulStop(reason = command.reason)
				false
			} else {
				requestGracefulStop(
					reason = command.reason,
					externalCommand = command,
				)
				true
			}
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
			trackingRolloutStateStore = trackingRolloutStateStore,
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
				val reconfigured = if (foregroundReady) runCatching {
					sourceSession.reconfigure(sourcePlanInputs(settings, demands))
				}.getOrNull() else null
				if (reconfigured == null || reconfigured is SourceSessionReconfigureOutcome.Rejected) {
					requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
				}
			},
		)
		createCycleDispatcher()
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
		latestDeliveredStartId = maxOf(latestDeliveredStartId, startId)

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
		val isPassiveAndroidDelivery = isPassivePreparedStartDelivery(flags)
		val deliveredStart = resolveRebasedStartDelivery(
			preparedStartRuntime,
			preparedToken,
			startCommand,
		) { trackingLifecycleCommandAuthority.resolveStart(startCommand) }
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
		val deliveredRuntime = preparedStartRuntime
		preparedStartRuntime = if (deliveredRuntime.matches(preparedToken, startCommand)) {
			deliveredRuntime.copy(applied = false)
		} else {
			PreparedStartRuntimeState(preparedToken, startCommand, applied = false)
		}
		val startupWasReadyAtDelivery = trackingStartupGate.isReady
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
			startSingleFlight.set(false)
			preparedStartRuntime = PreparedStartRuntimeState()
			return discardPreparedStart(
				preparedToken,
				startCommand,
				startId,
				"PREPARED_FOREGROUND_PROMOTION_UNAVAILABLE",
			)
		}
		val startupCompletionDeadlineNanos = SystemClock.elapsedRealtimeNanos().saturatedAdd(
			(if (startupWasReadyAtDelivery) {
				PRE_FOREGROUND_START_BUDGET_MILLIS
			} else {
				STARTUP_FOREGROUND_SHELL_MAX_MILLIS
			}) * NANOS_PER_MILLISECOND,
		)
		sessionRecoveryJob = launch {
			if (!awaitTrackingStartupBefore(startupCompletionDeadlineNanos)) {
				abandonPreparedStartShell(startId, "TRACKING_STARTUP_SHELL_TIMEOUT")
				return@launch
			}
			var effectiveToken = preparedToken
			var effectiveCommand = startCommand
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
					Tracebox.log.error(failure, "Tracking redelivery resolution failed")
					AndroidRedeliveryStartResolution.Rejected("TRACKING_PRE_FOREGROUND_FAILED")
				}
				if (redelivery == null) {
					abandonPreparedStartShell(startId, "TRACKING_STARTUP_SHELL_TIMEOUT")
					return@launch
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
						effectiveToken = redelivery.preparation.token
						effectiveCommand = redelivery.command
						supersedeOlderExternalStop(redelivery.command)
						preparedStartRuntime = PreparedStartRuntimeState(
							effectiveToken,
							effectiveCommand,
							applied = false,
							pendingPredecessor = RebasedPredecessorDelivery(
								replacementToken = effectiveToken,
								replacementCommandGeneration = effectiveCommand.generation,
								predecessorStartId = startId,
							),
						)
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
										Tracebox.log.error(failure, "Tracking redelivery rebase enqueue failed")
										false
									}
									if (enqueued) {
										preparedStartRuntime = preparedStartRuntime.copy(platformOwned = true)
									} else {
										startSingleFlight.compareAndSet(false, true)
									}
									enqueued
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
							if (preparedStartRuntime.platformOwned) return@launch
							startSingleFlight.compareAndSet(false, true)
							rejectPreparedStart(
								effectiveToken,
								effectiveCommand,
								startId,
								"REDELIVERY_REBASE_TIMEOUT",
							)
							return@launch
						}
						when (rebase) {
							is AndroidRedeliveryRebaseResult.Rebased -> {
								if (!rebase.enqueueAcknowledged) {
									Tracebox.log.warn(
										"Rebased tracking start was accepted by Android without " +
											"an exact enqueue acknowledgement",
									)
								}
								return@launch
							}
							AndroidRedeliveryRebaseResult.EnqueueFailed -> {
								startSingleFlight.compareAndSet(false, true)
								rejectPreparedStart(
									effectiveToken,
									effectiveCommand,
									startId,
									"REDELIVERY_REBASE_ENQUEUE_FAILED",
								)
								return@launch
							}
							AndroidRedeliveryRebaseResult.Stale -> {
								startSingleFlight.compareAndSet(false, true)
								rejectPreparedStart(
									effectiveToken,
									effectiveCommand,
									startId,
									"REDELIVERY_REBASE_COMMAND_STALE",
								)
								return@launch
							}
							is AndroidRedeliveryRebaseResult.BlockedByStop -> {
								startSingleFlight.compareAndSet(false, true)
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
								return@launch
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
						return@launch
					}
					is AndroidRedeliveryStartResolution.Rejected -> {
						rejectPreparedStart(
							effectiveToken,
							effectiveCommand,
							startId,
							redelivery.failureCode,
						)
						return@launch
					}
				}
				preparedStartRuntime = PreparedStartRuntimeState(
					effectiveToken,
					effectiveCommand,
					applied = false,
				)
			}
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
					when (val acceptance =
						trackingLifecycleCommandAuthority
							.withCurrentStart<TrackingServicePreparedStartClaim>(
							effectiveCommand,
							acceptWhen = { result ->
								result is TrackingServicePreparedStartClaim.Claimed
							},
						) startAcceptance@{
							if (gracefulStopRequested) {
								return@startAcceptance TrackingServicePreparedStartClaim.Rejected(
										"START_SUPERSEDED_BEFORE_FOREGROUND",
									)
							}
							this@TrackerService.sessionInfo =
								TrackerSessionInfo(claimed.claim.isUserInitiated)
							activeSessionDescriptor = claimed.descriptor
							sessionStartOrigin = claimed.claim.startOrigin
							if (!ensureForegroundStarted(
								claimed.claim.acceptedSources,
								stopServiceOnFailure = false,
							)) return@startAcceptance TrackingServicePreparedStartClaim.Rejected(
									"TRACKING_FOREGROUND_PROMOTION_FAILED",
								)
							val appliedMask = activeForegroundServiceType?.toLong() ?: 0L
							if (appliedMask != claimed.claim.desiredForegroundCapabilityFlags) {
								return@startAcceptance TrackingServicePreparedStartClaim.Rejected(
										"TRACKING_FOREGROUND_TYPE_MISMATCH",
									)
							}
							if (!trackingStartRequestCoordinator.markForegroundAccepted(
								claimed.claim,
								effectiveCommand.generation,
								claimed.startupGeneration,
							)) TrackingServicePreparedStartClaim.Rejected(
								"TRACKING_FOREGROUND_ACCEPTANCE_STALE",
							) else claimed
						}
					) {
						is LockedTrackingStartResult.Executed -> acceptance.value
						LockedTrackingStartResult.Stale -> TrackingServicePreparedStartClaim.Rejected(
							"START_COMMAND_STALE_BEFORE_FOREGROUND_ACCEPTANCE",
						)
						is LockedTrackingStartResult.BlockedByStop ->
							TrackingServicePreparedStartClaim.Rejected(
								"START_BLOCKED_BY_STOP_BEFORE_FOREGROUND_ACCEPTANCE",
							)
					}
				}
			} catch (failure: Exception) {
				Tracebox.log.error(failure, "Tracking prepared start failed before foreground acceptance")
				TrackingServicePreparedStartClaim.Rejected("TRACKING_PRE_FOREGROUND_FAILED")
			}
			if (prepared == null) {
				abandonPreparedStartShell(startId, "TRACKING_STARTUP_SHELL_TIMEOUT")
				return@launch
			}
			if (prepared !is TrackingServicePreparedStartClaim.Claimed) {
				val rejection = prepared as? TrackingServicePreparedStartClaim.Rejected
					?: error("Deferred claim escaped the startup wait loop")
				try {
					runBoundedStartPreparationCancellationCleanup(null) {
						trackingStartRequestCoordinator.compensate(
							effectiveToken,
							effectiveCommand,
							rejection.failureCode,
						)
					}
				} finally {
					rollbackRejectedPreparedStartRuntime()
					startSingleFlight.set(false)
					discardStartRequest(startId)
				}
				return@launch
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
		}
		return START_REDELIVER_INTENT
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

	/** Stops the providerless foreground shell without forcing Room through a closed startup gate. */
	private fun abandonPreparedStartShell(startId: Int, failureCode: String) {
		Tracebox.log.error("Tracking prepared-start shell stopped: {}", failureCode)
		TrackerNotificationManager.postStartFailedNotification(this)
		rollbackRejectedPreparedStartRuntime()
		startSingleFlight.set(false)
		discardStartRequest(startId)
	}

	private fun rollbackRejectedPreparedStartRuntime() {
		this.sessionInfo = null
		activeSessionDescriptor = null
		sessionStartOrigin = null
		preparedStartRuntime = PreparedStartRuntimeState()
		if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
		foregroundStarted = false
		foregroundIsStartupShell = false
		activeForegroundServiceType = null
		activeForegroundRequirements = null
	}

	private suspend fun rejectPreparedStart(
		token: PreparedTrackingStartToken,
		command: TrackingStartCommand,
		startId: Int,
		failureCode: String,
	) {
		try {
			trackingStartRequestCoordinator.compensate(token, command, failureCode)
		} finally {
			rollbackRejectedPreparedStartRuntime()
			startSingleFlight.set(false)
			discardStartRequest(startId)
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
		if (command != null) launch {
			trackingStartRequestCoordinator.compensate(token, command, failureCode)
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
		Tracebox.log.debug("Tracking session start requested")
		gracefulStopRequested = false
		stopReason = TrackingStopCandidateReason.UNKNOWN
		coordinatorMetricBaseline = coordinatorTelemetry.snapshot()
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
			suspend fun compensatePreparedStart(failureCode: String) {
				if (preparedStartRuntime.applied) return
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
					saveActiveSession(provisionalDescriptor)

					// Resolve semantic source plans before starting the event-owned session runtimes.
					val rolloutState = trackingRolloutStateStore.load()
					sessionRolloutState = rolloutState
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
						activeSessionDescriptor = null
						startSingleFlight.set(false)
						requestGracefulStop(
							startId,
							TrackingStopCandidateReason.PERMISSION_UNAVAILABLE,
						)
						return@runAfter
					}
					val acceptedForegroundSources = currentAcceptedForegroundSources
					if (acceptedForegroundSources.isEmpty()) {
						compensatePreparedStart("TRACKING_CAPTURE_UNAVAILABLE_BEFORE_APPLY")
						activeTrackingSessionStore.clearExact(provisionalDescriptor)
						activeSessionDescriptor = null
						startSingleFlight.set(false)
						requestGracefulStop(
							startId,
							TrackingStopCandidateReason.CAPTURE_UNAVAILABLE,
						)
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
					if (!ensureForegroundStarted(acceptedForegroundSources)) {
						compensatePreparedStart("TRACKING_FOREGROUND_LOST_BEFORE_APPLY")
						return@runAfter
					}
					val descriptor = provisionalDescriptor.copy(policyTier = initialTier)
					activeSessionDescriptor = descriptor
					if (descriptor != provisionalDescriptor) {
						saveActiveSession(descriptor)
					}
					preparedStartRuntime = preparedStartRuntime.copy(applyStarted = true)
					val sourceStartFailure: Any? = when (val result = trackingStartRequestCoordinator.apply(
						preparedTrackingStart,
						startCommandGeneration,
					)) {
						is com.adsamcik.tracker.tracker.source.coordinator.SessionStartResult.Started -> {
							preparedStartRuntime = preparedStartRuntime.copy(applied = true)
							null
						}
						null -> "TRACKING_STARTUP_GENERATION_CLOSED_BEFORE_APPLY"
						else -> result
					}
					if (sourceStartFailure != null) {
							compensatePreparedStart("PREPARED_SOURCE_APPLY_REJECTED")
							activeTrackingSessionStore.clearExact(descriptor)
							activeSessionDescriptor = null
							startSingleFlight.set(false)
							Tracebox.log.error("Event source session start rejected: $sourceStartFailure")
							requestGracefulStop(
								startId,
								TrackingStopCandidateReason.INITIALIZATION_FAILURE,
							)
							return@runAfter
					}

					controller.updateServiceRunning(true)
					this@TrackerService.sessionInfo = TrackerSessionInfo(isUserInitiated)
					controller.updateSessionInfo(this@TrackerService.sessionInfo)
					if (runtimePermissionReconfigurePending.getAndSet(false)) {
						reconfigureActiveSessionForRuntimePermissions()
					}
					startSingleFlight.set(false)
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
					observeDescriptorTierChanges(descriptor)
					if (!quiesceCycleDispatcherForReplacement()) {
						requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
						return@runAfter
					}
					createCycleDispatcher()
					withContext(dispatchers.default) {
						orchestrator.initialize(
							context = this@TrackerService,
							isSessionUserInitiated = isUserInitiated,
							initialTier = initialTier,
							scope = this@TrackerService,
							logicalTrackingId = descriptor.logicalTrackingId,
							serviceRunId = descriptor.serviceRunId,
							rolloutState = rolloutState,
						)
					}
					collectionMotionController.startSession(
						descriptor.logicalTrackingId,
						descriptor.serviceRunId,
						SystemClock.elapsedRealtimeNanos(),
						initialMotion = !isUserInitiated && !isRecovery,
					)
					sourcePipelineRecovery.drainCommittedWork()
					observeCollectionMotionPolicy()

					batteryObservationJob?.cancel()
					batteryObservationJob = launch {
						batteryAwarePolicy.batteryLevelUpdates.collect {
							orchestrator.onBatteryLevelChanged(scope = this@TrackerService)
							val currentSettings = trackingParamsRepository.data.first()
							val sourceResult = runCatching {
								sourceSession.reconfigure(
									sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
								)
							}.getOrNull()
							if (sourceResult == null || sourceResult is SourceSessionReconfigureOutcome.Rejected) {
								requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
							}
						}
					}

				}
			} catch (e: TimeoutCancellationException) {
				withContext(NonCancellable) {
					compensatePreparedStart("PREPARED_START_INITIALIZATION_TIMEOUT")
				}
				startSingleFlight.set(false)
				Tracebox.log.error(e, "Tracking start failed")
				requestGracefulStop(reason = TrackingStopCandidateReason.INITIALIZATION_FAILURE)
			} catch (e: CancellationException) {
				withContext(NonCancellable) {
					compensatePreparedStart("PREPARED_START_INITIALIZATION_CANCELLED")
				}
				throw e
			} catch (e: Exception) {
				compensatePreparedStart("PREPARED_START_INITIALIZATION_FAILED")
				startSingleFlight.set(false)
				Tracebox.log.error(e, "Tracking start failed")
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
				.drop(1)
				.distinctUntilChanged()
				.collect { tier ->
					if (tier == PolicyTier.OFF) return@collect
					val updated = initialDescriptor.copy(policyTier = tier)
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
						val result = runCatching {
							sourceSession.reconfigure(
								sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
							)
						}.getOrNull()
						if (result == null || result is SourceSessionReconfigureOutcome.Rejected) {
							requestGracefulStop(reason = TrackingStopCandidateReason.INTERNAL_FAILURE)
						}
					}
			}
			while (isActive) {
				delay(MOTION_POLICY_TICK_MILLIS)
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
					Tracebox.log.error(failure, "Runtime permission snapshot reconciliation failed")
				}
				collectionMotionController.tick(SystemClock.elapsedRealtimeNanos())
			}
		}
	}

	private suspend fun onRuntimePermissionChanged() {
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
			sourceSession.reconfigure(
				sourcePlanInputs(currentSettings, orchestrator.currentSourceDemands()),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: Exception) {
			Tracebox.log.error(failure, "Runtime permission source reconciliation failed")
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

	private fun refreshForegroundForRuntimePermissions(settings: TrackingParamsState): Set<SourceKind>? {
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
		acceptedSources: Set<SourceKind>,
		stopServiceOnFailure: Boolean = true,
		notificationIsUserInitiatedHint: Boolean? = null,
		startupShell: Boolean = false,
	): Boolean {
		if (acceptedSources.isEmpty()) {
			onForegroundStartFailed(stopServiceOnFailure)
			return false
		}
		val requirements = ForegroundServiceRequirements(acceptedSources)

		if (requirements.requiresLocation && !hasLocationPermission) {
			onForegroundStartFailed(stopServiceOnFailure)
			return false
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			if (foregroundStarted && activeForegroundRequirements == requirements &&
				foregroundIsStartupShell == startupShell
			) return true
			val notification = foregroundNotification(
				requirements,
				notificationIsUserInitiatedHint,
				startupShell,
			)
			return if (tryStartForeground(notification, type = null)) {
				foregroundStarted = true
				foregroundIsStartupShell = startupShell
				activeForegroundServiceType = null
				activeForegroundRequirements = requirements
				onForegroundServiceTypeChanged()
				true
			} else {
				onForegroundStartFailed(stopServiceOnFailure)
				false
			}
		}

		val candidates = foregroundServiceTypeCandidates(
			sdkInt = Build.VERSION.SDK_INT,
			acceptedSources = acceptedSources,
		)
		val preferredType = candidates.firstOrNull()
		if (preferredType == null && candidates.isEmpty()) {
			onForegroundStartFailed(stopServiceOnFailure)
			return false
		}
		if (
			foregroundStarted &&
			activeForegroundServiceType == preferredType &&
			activeForegroundRequirements == requirements &&
			foregroundIsStartupShell == startupShell
		) {
			return true
		}

		val notification = foregroundNotification(
			requirements,
			notificationIsUserInitiatedHint,
			startupShell,
		)
		for (type in candidates) {
			if (tryStartForeground(notification, type)) {
				foregroundStarted = true
				foregroundIsStartupShell = startupShell
				activeForegroundServiceType = type
				activeForegroundRequirements = requirements
				onForegroundServiceTypeChanged()
				return true
			}
		}
		onForegroundStartFailed(stopServiceOnFailure)
		return false
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
			Tracebox.log.error("Tracking start failed")
			TrackerNotificationManager.postStartFailedNotification(this)
			requestGracefulStop(reason = TrackingStopCandidateReason.PERMISSION_UNAVAILABLE)
		}
	}

	private suspend fun processCycleUpdate(cycle: TrackingCycle) {
		val wakeLockStartedAtNanos = Time.elapsedRealtimeNanos
		wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
		try {
			Tracebox.log.performanceSuspend("Process tracking cycle") {
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
			false
		}
		cycleDispatcherScope?.cancel()
		cycleDispatcherScope = null
		if (!drained || !cancelled) {
			Tracebox.log.warn("Tracking shutdown was degraded")
		}
		return cancelled
	}

	@Synchronized
	private fun requestGracefulStop(
		startId: Int? = null,
		reason: TrackingStopCandidateReason = TrackingStopCandidateReason.EXPLICIT_REQUEST,
		externalCommand: TrackingStopCommand? = null,
	) {
		if (externalCommand != null &&
			!trackingLifecycleCommandAuthority.isStopActionable(externalCommand)
		) return
		if (gracefulStopRequested) {
			if (externalCommand == null) return
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
			val stopAfterStartId = startId ?: latestDeliveredStartId.takeIf { it > 0 }
			launch { completeExternalStopDelivery(externalCommand, stopAfterStartId) }
			return
		}
		Tracebox.log.debug("Tracking stop requested: {}", reason)
		gracefulStopRequested = true
		stopReason = reason
		activeExternalStop = externalCommand?.let { command ->
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
			reason = reason,
			changedAtEpochMs = activeExternalStop?.cutoff?.wallTimeMs ?: System.currentTimeMillis(),
		)
		activeSessionDescriptor = stopCandidate
		val stopAfterStartId = startId ?: latestDeliveredStartId.takeIf { it > 0 }
		launch {
			// Keep the stop candidate durable until teardown completes.  An unexpected kill in this
			// interval must not be interpreted as a user-session crash eligible for restart.
			if (stopCandidate != null) {
				saveActiveSession(stopCandidate)
			}
			if (externalCommand == null) {
				if (stopAfterStartId == null) stopSelf() else stopSelfResult(stopAfterStartId)
			} else {
				completeExternalStopDelivery(externalCommand, stopAfterStartId)
			}
		}
	}

	private suspend fun completeExternalStopDelivery(
		command: TrackingStopCommand,
		stopAfterStartId: Int?,
	) {
		if (!trackingLifecycleCommandAuthority.isStopActionable(command)) {
			withdrawSupersededExternalStop(command)
			return
		}
		val stopped = if (stopAfterStartId == null) {
			stopSelf()
			true
		} else {
			stopSelfResult(stopAfterStartId)
		}
		if (!stopped) withdrawSupersededExternalStop(command)
	}

	private fun discardStartRequest(startId: Int): Int {
		stopSelfResult(startId)
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

	@Synchronized
	private fun supersedeOlderExternalStop(command: TrackingStartCommand) {
		val stop = activeExternalStop?.command ?: return
		if (command.generation <= stop.generation ||
			trackingLifecycleCommandAuthority.isStopCurrent(stop)
		) return
		gracefulStopRequested = false
		stopReason = TrackingStopCandidateReason.UNKNOWN
		activeExternalStop = null
		startSingleFlight.set(false)
		val restored = activeSessionDescriptor?.withdrawStopCandidate(System.currentTimeMillis())
		activeSessionDescriptor = restored
		if (restored != null) launch { saveActiveSession(restored) }
	}

	private suspend fun withdrawSupersededExternalStop(command: TrackingStopCommand) {
		if (activeExternalStop?.command != command) return
		if (trackingLifecycleCommandAuthority.isStopCurrent(command)) return
		gracefulStopRequested = false
		stopReason = TrackingStopCandidateReason.UNKNOWN
		activeExternalStop = null
		startSingleFlight.set(false)
		val restored = activeSessionDescriptor?.withdrawStopCandidate(System.currentTimeMillis())
		activeSessionDescriptor = restored
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
			Tracebox.log.error("Tracking session store failed")
			return
		}
		// Do not overwrite a newer service run's descriptor if it won the atomic comparison.
		if ((result as ActiveTrackingSessionStoreResult.Success).descriptor == null) {
			activeSessionDescriptor = null
			markLatestExternalStopHandled()
		}
	}

	private suspend fun markLatestExternalStopHandled() {
		while (true) {
			val command = activeExternalStop?.command ?: return
			if (trackingLifecycleCommandAuthority.markStopHandled(command)) return
			if (activeExternalStop?.command == command) return
		}
	}

	private suspend fun saveActiveSession(descriptor: ActiveTrackingSessionDescriptor) {
		if (activeTrackingSessionStore.save(descriptor) is ActiveTrackingSessionStoreResult.Failure) {
			Tracebox.log.error("Tracking session store failed")
		}
	}

	override fun onDestroy() {
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
		startSingleFlight.set(false)
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
					Tracebox.log.error(
						failure,
						"Tracking provider teardown remains incomplete after $attempt attempts",
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
				sourceSession.stop(
					reason = stopReason.name,
					preserveLogicalSession = !gracefulStopRequested,
					factualCutoff = activeExternalStop?.cutoff,
				)
			}
			serviceRunId?.let(collectionMotionController::stopSession)
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
			} catch (e: Exception) {
				finalCycleCancellationFailure = e
			}
		}
		if (
			!shutdownSequence.drained ||
			shutdownSequence.drainFailure != null ||
			finalCycleCancellationFailure != null ||
			shutdownSequence.shutdownResult == null
		) {
			Tracebox.log.warn("Tracking shutdown was degraded")
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
		coordinatorMetricBaseline?.let { baseline ->
			Tracebox.log.debug(
				"Tracking coordinator session metrics: {}",
				coordinatorTelemetry.snapshot() - baseline,
			)
		}
		coordinatorMetricBaseline = null
		if (preparedRuntime.applied) {
			HistoricalTrajectoryReconstructionWorker.schedule(context)
		}
	}

	private suspend fun sourcePlanInputs(
		settings: TrackingParamsState,
		demands: List<SourceDemand>,
	): SourceSessionPlanInputs {
		val packageManager = packageManager
		val foreground = activeForegroundRequirements
		val startOrigin = sessionStartOrigin ?: SessionStartOrigin.POLICY_RECONCILIATION
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
		val constraints = mapOf(
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
				permissionGranted = hasWifiScanPermission,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.WIFI) == true,
			),
			SourceKind.CELL to SourceConstraint(
				hardwareAvailable = cellFeature,
				permissionGranted = hasCellScanPermission,
				foregroundCapabilityLegal = foreground?.accepts(SourceKind.CELL) == true,
			),
		)
		return SourceSessionPlanInputs(
			settings = settings,
			environment = SourcePlanEnvironment(
				locationBackend = TrackerTimerManager.getSelectedLocationBackend(this),
				preciseLocationAvailable = ContextCompat.checkSelfPermission(
					this,
					Manifest.permission.ACCESS_FINE_LOCATION,
				) == PackageManager.PERMISSION_GRANTED,
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
		val descriptor = activeSessionDescriptor ?: return false
		val ownership = resolveActiveSessionOwnership(rollout, settings, descriptor)
		val accepted = resolveAcceptedForegroundSources(
			requestedSources = ownership.enabledEventSources,
			startOrigin = sessionStartOrigin ?: SessionStartOrigin.POLICY_RECONCILIATION,
		)
		return accepted.isNotEmpty() && ensureForegroundStarted(accepted)
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
				wifi = wifiFeature && hasWifiScanPermission,
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
