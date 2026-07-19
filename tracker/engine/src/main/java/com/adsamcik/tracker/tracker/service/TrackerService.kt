package com.adsamcik.tracker.tracker.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.extension.hasActivityPermission
import com.adsamcik.tracker.shared.base.extension.hasLocationPermission
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackerServiceContract
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.NoTimer
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.trigger.AmbientCollectionTrigger
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.policy.BatteryAwarePolicy
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationChannels
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.receiver.TrackerRestartReceiver
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionDescriptor
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStore
import com.adsamcik.tracker.tracker.resilience.ActiveTrackingSessionStoreResult
import com.adsamcik.tracker.tracker.resilience.shouldScheduleTrackerRestart
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Android lifecycle shell for the tracking service.
 *
 * Owns Android-specific concerns (foreground notification, wake lock, timer,
 * shortcuts, intent parsing) and delegates all business logic to
 * [TrackingOrchestrator].
 */
@AndroidEntryPoint
internal class TrackerService : CoreService(), TrackerTimerReceiver {
	private lateinit var powerManager: PowerManager
	private lateinit var wakeLock: PowerManager.WakeLock

	@Inject
	lateinit var controller: TrackerServiceController

	@Inject
	lateinit var lockManager: LockManager

	@Inject
	lateinit var trackerListenerManager: TrackerListenerManager

	@Inject
	lateinit var signalProcessors: Set<@JvmSuppressWildcards SignalProcessor>

	@Inject
	lateinit var domainEventRepository: DomainEventRepository

	@Inject
	lateinit var trackingParamsRepository: TrackingParamsRepository

	@Inject
	lateinit var trackerSettingsRepository: TrackerSettingsRepository

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

	private lateinit var orchestrator: TrackingOrchestrator

	private var lockObservationJob: Job? = null
	private var initializationJob: Job? = null
	private var batteryObservationJob: Job? = null
	private var sessionRecoveryJob: Job? = null
	private var descriptorObservationJob: Job? = null
	private var timerComponent: CollectionTriggerComponent = NoTimer()
	private lateinit var cycleDispatcher: TrackingCycleDispatcher
	private var cycleDispatcherScope: CoroutineScope? = null
	private var serviceGeneration: Long = 0L

	// Kept here for intent recovery and power-save check
	private var sessionInfo: TrackerSessionInfo? = null
	private var activeSessionDescriptor: ActiveTrackingSessionDescriptor? = null
	private var gracefulStopRequested = false
	private val restartScheduled = AtomicBoolean(false)

	override fun onCreate() {
		super.onCreate()
		synchronized(SERVICE_GENERATION_LOCK) {
			serviceGeneration = SERVICE_GENERATION_COUNTER.incrementAndGet()
			activeServiceGeneration = serviceGeneration
		}

		// At onCreate the start intent is not yet available, so prefer the location type only
		// when location permission is held (covers GPS sessions); otherwise start as a health
		// (activity-recognition) service. onStartCommand refines the type once the session mode
		// is known.
		ensureForegroundStarted(preferLocationType = hasLocationPermission)

		powerManager = getSystemServiceTyped(Context.POWER_SERVICE)
		wakeLock = powerManager.newWakeLock(
			PowerManager.PARTIAL_WAKE_LOCK,
			"signals:TrackerWakeLock"
		)

		orchestrator = TrackingOrchestrator(
			controller = controller,
			trackerListenerManager = trackerListenerManager,
			signalProcessors = signalProcessors,
			domainEventRepository = domainEventRepository,
			dispatchers = dispatchers,
			appDatabase = appDatabase,
			trackingParamsRepository = trackingParamsRepository,
			trackerSettingsRepository = trackerSettingsRepository,
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
			requestGracefulStop(startId)
			return START_NOT_STICKY
		}

		val recoveredSessionInfo = sessionInfo ?: controller.sessionInfoFlow.value
		val isWatchdogRestart = intent?.hasExtra(ARG_POLICY_TIER) == true
		if (isWatchdogRestart && sessionInfo != null && !gracefulStopRequested) {
			Reporter.log("Ignoring watchdog restart because this service instance is already active")
			return if (sessionInfo?.isInitiatedByUser == true) {
				START_REDELIVER_INTENT
			} else {
				START_NOT_STICKY
			}
		}
		if (intent == null && recoveredSessionInfo == null) {
			sessionRecoveryJob?.cancel()
			sessionRecoveryJob = launch {
				when (val result = activeTrackingSessionStore.read()) {
					is ActiveTrackingSessionStoreResult.Success -> {
						val descriptor = result.descriptor
						?.takeIf { it.isUserInitiated }
						?: run {
							Reporter.w(
								"TrackerService",
								"Restarted with null intent and no durable user session; stopping service",
							)
							requestGracefulStop(startId)
							return@launch
						}
						Reporter.w(
							"TrackerService",
							"Restarted with null intent; recovering durable user session",
						)
						beginSession(descriptor, startId)
					}
					is ActiveTrackingSessionStoreResult.Failure -> {
						Reporter.report(result.cause)
						requestGracefulStop(startId)
					}
				}
			}
			return START_REDELIVER_INTENT
		}

		val isUserInitiated = intent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
			?: recoveredSessionInfo?.isInitiatedByUser
			?: DEFAULT_IS_USER_INITIATED
		val isAmbient = intent?.getBooleanExtra(ARG_IS_AMBIENT, false)
			?: !isUserInitiated
		val recoveredTier = if (intent == null) {
			controller.policyTierFlow.value.takeUnless { it == PolicyTier.OFF }
		} else {
			intent.getStringExtra(ARG_POLICY_TIER)
				?.let { name -> PolicyTier.entries.firstOrNull { it.name == name } }
		}
		beginSession(
			ActiveTrackingSessionDescriptor(
				isUserInitiated = isUserInitiated,
				isAmbient = isAmbient,
				policyTier = recoveredTier ?: PolicyTier.OFF,
			),
			startId,
		)

		return if (isUserInitiated) START_REDELIVER_INTENT else START_NOT_STICKY
	}

	private fun beginSession(
		startDescriptor: ActiveTrackingSessionDescriptor,
		startId: Int,
	) {
		gracefulStopRequested = false
		restartScheduled.set(false)
		val isUserInitiated = startDescriptor.isUserInitiated
		val isAmbient = startDescriptor.isAmbient
		val provisionalDescriptor = startDescriptor.copy(
			policyTier = resolveInitialPolicyTier(
				isUserInitiated = isUserInitiated,
				isAmbient = isAmbient,
				locationEnabled = false,
				recoveredTier = startDescriptor.policyTier.takeUnless { it == PolicyTier.OFF },
			),
		)
		activeSessionDescriptor = provisionalDescriptor

		// Re-assert the foreground service now the start command is being processed. Auto-started
		// sessions can request GPS immediately when location tracking is enabled, and the FGS type is
		// fixed at start (it is never re-declared on tier escalation), so a session that may record a
		// route must advertise the location type up front or Android blocks its background location
		// access. Only when location permission is absent (activity/steps-only tracking) do we fall
		// back to the health type, which also prevents the Android 14+ startForeground crash.
		ensureForegroundStarted(preferLocationType = hasLocationPermission)

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
					if (isLocked) requestGracefulStop()
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
					when (val result = activeTrackingSessionStore.save(provisionalDescriptor)) {
						is ActiveTrackingSessionStoreResult.Success -> Unit
						is ActiveTrackingSessionStoreResult.Failure -> Reporter.report(result.cause)
					}
					timerComponent.onDisable(this@TrackerService)
					timerComponent = NoTimer()
					if (!quiesceCycleDispatcherForReplacement()) {
						requestGracefulStop()
						return@runAfter
					}
					createCycleDispatcher()

					// The collection trigger is chosen from BOTH the battery tier and whether the user
					// wants location. A GPS trigger only runs when location is enabled AND the tier is
					// GPS-capable; otherwise the lightweight non-GPS trigger drives cycles so any
					// combination of Wi-Fi/cell/activity/step sources is still collected without GPS.
					val trackingParams = trackingParamsRepository.data.first()
					val locationEnabled = trackingParams.locationEnabled
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
					val descriptor = ActiveTrackingSessionDescriptor(
						isUserInitiated = isUserInitiated,
						isAmbient = isAmbient,
						policyTier = initialTier,
					)
					activeSessionDescriptor = descriptor
					if (descriptor != provisionalDescriptor) {
						when (val result = activeTrackingSessionStore.save(descriptor)) {
							is ActiveTrackingSessionStoreResult.Success -> Unit
							is ActiveTrackingSessionStoreResult.Failure -> Reporter.report(result.cause)
						}
					}
					controller.updatePolicyTier(initialTier)
					observeDescriptorTierChanges(descriptor)
					val useGpsTrigger = locationEnabled && initialTier.isGpsEnabled
					timerComponent = if (useGpsTrigger) {
						TrackerTimerManager.getSelected(this@TrackerService, dispatchers.main)
					} else {
						AmbientCollectionTrigger(dispatchers.main)
					}

					val timerAccessor = object : TrackerTierEscalationHandler.TimerAccessor {
						override fun get() = timerComponent
						override fun set(timer: CollectionTriggerComponent) {
							timerComponent = timer
						}
					}

					withContext(dispatchers.default) {
						orchestrator.initialize(
							context = this@TrackerService,
							isSessionUserInitiated = isUserInitiated,
							initialTier = initialTier,
							scope = this@TrackerService,
							timerReceiver = this@TrackerService,
							timerAccessor = timerAccessor,
						)
					}

					batteryObservationJob?.cancel()
					batteryObservationJob = launch {
						batteryAwarePolicy.batteryLevelUpdates.collect {
							orchestrator.onBatteryLevelChanged(
								context = this@TrackerService,
								timerReceiver = this@TrackerService,
								scope = this@TrackerService,
							)
						}
					}

					if (timerComponent.hasRequiredPermissions(this@TrackerService)) {
						timerComponent.onEnable(this@TrackerService, this@TrackerService)
					} else {
						Reporter.report("Missing permissions for ${timerComponent.javaClass}")
						requestGracefulStop()
					}
				}
			} catch (e: TimeoutCancellationException) {
				Reporter.report(IllegalStateException("Timed out waiting for prior tracking lifecycle work", e))
				requestGracefulStop()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Reporter.report(IllegalStateException("Failed to initialize tracking service", e))
				requestGracefulStop()
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
					when (val result = activeTrackingSessionStore.save(updated)) {
						is ActiveTrackingSessionStoreResult.Success -> Unit
						is ActiveTrackingSessionStoreResult.Failure -> Reporter.report(result.cause)
					}
				}
		}
	}

	/**
	 * Starts the service in the foreground with the most appropriate foreground-service type.
	 *
	 * On Android 14+ the platform rejects [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION] unless
	 * location permission is held, and the FGS type is fixed at start (it is not re-declared on tier
	 * escalation). Auto-started sessions can be GPS-capable immediately once activity recognition
	 * has confirmed movement, so whenever location permission is held we advertise the location type
	 * up front to keep background location access working. Only when location permission is absent —
	 * activity/steps-only tracking that can never use GPS — do we fall back to the health type
	 * (backed by ACTIVITY_RECOGNITION); this also avoids the startForeground crash that
	 * unconditionally declaring the location type caused for those sessions. If no declared type can
	 * be started the service stops instead of crashing.
	 *
	 * @param preferLocationType whether the location type should be preferred (location permission
	 * is held, so the session may use or escalate to GPS).
	 */
	private fun ensureForegroundStarted(preferLocationType: Boolean) {
		TrackerNotificationChannels.ensureTrackingChannel(this)
		val notification = TrackerNotificationManager.getForegroundNotification(this)

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			if (!tryStartForeground(notification, type = null)) onForegroundStartFailed()
			return
		}

		val candidates = foregroundServiceTypeCandidates(
			sdkInt = Build.VERSION.SDK_INT,
			preferLocationType = preferLocationType,
			hasLocationPermission = hasLocationPermission,
			hasActivityPermission = hasActivityPermission,
		)
		val started = candidates.any { type -> tryStartForeground(notification, type) }
		if (!started) onForegroundStartFailed()
	}

	private fun tryStartForeground(notification: Notification, type: Int?): Boolean = try {
		if (type == null) {
			startForeground(TrackerNotificationManager.NOTIFICATION_ID, notification)
		} else {
			startForeground(TrackerNotificationManager.NOTIFICATION_ID, notification, type)
		}
		true
	} catch (exception: SecurityException) {
		Reporter.w("TrackerService", "startForeground rejected for FGS type=$type: ${exception.message}")
		false
	} catch (@Suppress("TooGenericExceptionCaught") exception: RuntimeException) {
		val isForegroundStartRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
			exception::class.java.name == "android.app.ForegroundServiceStartNotAllowedException"
		if (isForegroundStartRestricted) {
			Reporter.w(
				"TrackerService",
				"Foreground start not allowed from background: ${exception.message}",
			)
			false
		} else {
			throw exception
		}
	}

	private fun onForegroundStartFailed() {
		Reporter.w("TrackerService", "No permitted foreground-service type available; stopping service")
		requestGracefulStop()
	}

	override fun onUpdate(cycle: TrackingCycle): Job = cycleDispatcher.enqueue(cycle)

	private suspend fun processCycleUpdate(cycle: TrackingCycle) {
		wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
		try {
			orchestrator.onCycleUpdate(this@TrackerService, cycle)
		} catch (e: CancellationException) {
			throw e
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			Reporter.report(e)
		} finally {
			if (wakeLock.isHeld) wakeLock.release()
		}

		// Power-save check (Android concern, kept in service)
		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) {
			requestGracefulStop()
		}
	}

	private fun createCycleDispatcher() {
		val dispatcherScope = CoroutineScope(SupervisorJob() + dispatchers.default)
		cycleDispatcherScope = dispatcherScope
		cycleDispatcher = TrackingCycleDispatcher(
			scope = dispatcherScope,
			dispatcher = dispatchers.default,
			capacity = TRACKING_CYCLE_QUEUE_CAPACITY,
			onFailure = Reporter::report,
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
			Reporter.report(IllegalStateException("Failed to drain tracking cycles before restart", e))
			false
		}
		if (!drained) {
			Reporter.log("Tracker cycle drain timed out before session restart")
		}

		val cancelled = try {
			withTimeoutOrNull(CYCLE_CANCEL_TIMEOUT_MILLIS) {
				cycleDispatcher.cancelAndJoin()
				true
			} == true
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Reporter.report(IllegalStateException("Failed to cancel tracking cycles before restart", e))
			false
		}
		cycleDispatcherScope?.cancel()
		cycleDispatcherScope = null
		if (!cancelled) {
			Reporter.report("Tracking cycle cancellation timed out before session restart")
		}
		return cancelled
	}

	override fun onError(errorData: TrackerTimerErrorData) {
		when (errorData.severity) {
			TrackerTimerErrorSeverity.STOP_SERVICE -> requestGracefulStop()
			TrackerTimerErrorSeverity.REPORT -> Reporter.report(errorData.internalMessage)
			TrackerTimerErrorSeverity.NOTIFY_USER -> orchestrator.notificationComponent.onError(
				this,
				errorData.messageRes
			)
			TrackerTimerErrorSeverity.WARNING -> Reporter.log(errorData.internalMessage)
		}
	}

	private fun requestGracefulStop(startId: Int? = null) {
		if (gracefulStopRequested) return
		gracefulStopRequested = true
		descriptorObservationJob?.cancel()
		descriptorObservationJob = null
		launch {
			when (val result = activeTrackingSessionStore.clear()) {
				is ActiveTrackingSessionStoreResult.Success -> Unit
				is ActiveTrackingSessionStoreResult.Failure -> Reporter.report(result.cause)
			}
			activeSessionDescriptor = null
			if (startId == null) {
				stopSelf()
			} else {
				stopSelfResult(startId)
			}
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
		val timerRef = timerComponent
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
		try {
			timerRef.onDisable(context)
		} catch (e: Exception) {
			Reporter.report(IllegalStateException("Failed to disable collection trigger during shutdown", e))
		}

		super.onDestroy()
		stopForeground(STOP_FOREGROUND_REMOVE)

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
		try {
			timerComponent.onDisable(context)
		} catch (e: Exception) {
			Reporter.report(
				IllegalStateException("Failed to disable late collection trigger during shutdown", e)
			)
		} finally {
			timerComponent = NoTimer()
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
		shutdownSequence.drainFailure?.let { failure ->
			Reporter.report(IllegalStateException("Failed to drain tracking cycles during shutdown", failure))
		}
		shutdownSequence.shutdownFailure?.let { failure ->
			Reporter.report(IllegalStateException("Failed to finalize tracking shutdown", failure))
		}
		var finalCycleCancellationFailure = shutdownSequence.cycleCancellationFailure
		finalCycleCancellationFailure?.let { failure ->
			Reporter.report(IllegalStateException("Failed to cancel tracking cycles during shutdown", failure))
			if (::cycleDispatcher.isInitialized) {
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
					Reporter.report(
						IllegalStateException("Failed final tracking-cycle cancellation", e)
					)
				}
			}
		}
		if (!shutdownSequence.drained) {
			Reporter.log("Tracker cycle drain timed out; cancelled queued cycles before teardown")
		}
		if (
			finalCycleCancellationFailure != null ||
			shutdownSequence.shutdownResult == null
		) {
			Reporter.log("Tracker shutdown cleanup failed; enqueueing daily summary fallback")
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
	}

	companion object {
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

/**
 * Pure logic: ordered foreground-service types to attempt for a tracker session, most preferred
 * first.
 *
 * The runtime permission prerequisites differ per type — [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION]
 * needs ACCESS_FINE/COARSE_LOCATION and [ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH] needs
 * ACTIVITY_RECOGNITION — so only types whose prerequisites are met are offered, and the caller tries
 * them in order until one is accepted by `startForeground`. When [preferLocationType] is set (the
 * caller holds location permission and the session may use or escalate to GPS) the location type is
 * advertised first; otherwise the health type is preferred so an activity/steps-only session with no
 * location permission cannot crash `startForeground` on Android 14+.
 *
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] is appended as a terminal fallback (it has no
 * runtime-permission gate) so a session that holds neither location nor activity permission — for
 * example a user-started Wi-Fi/cell-only session — can still run a foreground service rather than
 * being stopped. It is unlike `dataSync`, which the platform time-caps and would prematurely kill a
 * long tracking session.
 *
 * Before Android 14 the location type is not permission-gated at `startForeground` time (matching
 * historical behaviour) and the health/special-use types do not exist, so only the location type is
 * returned.
 */
internal fun foregroundServiceTypeCandidates(
	sdkInt: Int,
	preferLocationType: Boolean,
	hasLocationPermission: Boolean,
	hasActivityPermission: Boolean,
): List<Int> {
	if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
		return listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
	}
	return buildList {
		if (preferLocationType && hasLocationPermission) {
			add(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
		}
		if (hasActivityPermission) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
		if (hasLocationPermission) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
		add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
	}.distinct()
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
