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
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.tracker.R
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
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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

	private lateinit var orchestrator: TrackingOrchestrator

	private var lockObservationJob: Job? = null
	private var initializationJob: Job? = null
	private var timerComponent: CollectionTriggerComponent = NoTimer()

	// Kept here for intent recovery and power-save check
	private var sessionInfo: TrackerSessionInfo? = null

	override fun onCreate() {
		super.onCreate()

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
			onDailySummaryWritten = {
				metricDirtyTracker.markDirty(
					com.adsamcik.tracker.stats.api.metric.MetricKeys.TABLE_DAILY_SUMMARY
				)
			},
		)

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

		val recoveredSessionInfo = sessionInfo ?: controller.sessionInfoFlow.value
		val resolvedIntent = intent ?: run {
			if (recoveredSessionInfo == null) {
				Reporter.w("TrackerService", "Restarted with null intent and no recoverable session state; stopping service")
				stopSelfResult(startId)
				return START_NOT_STICKY
			}

			Reporter.w("TrackerService", "Restarted with null intent; recovering with sticky defaults")
			null
		}

		val isUserInitiated = resolvedIntent?.getBooleanExtra(ARG_IS_USER_INITIATED, false)
			?: recoveredSessionInfo?.isInitiatedByUser
			?: DEFAULT_IS_USER_INITIATED
		val isAmbient = resolvedIntent?.getBooleanExtra(ARG_IS_AMBIENT, false)
			?: !isUserInitiated

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

		if (!isUserInitiated) {
			// Cancel any prior lock observer so a second START intent (e.g. Android
			// re-delivers START_STICKY while the first one is still mid-init) doesn't
			// leak a second collector that races stopSelf() against the new session.
			lockObservationJob?.cancel()
			lockObservationJob = launch {
				lockManager.isLockedFlow.collect { isLocked ->
					if (isLocked) stopSelf()
				}
			}
		}

		activityWatcherController.poke(trackerRunning = true)

		// Re-entrancy guard: a second onStartCommand arriving while the first
		// initialization coroutine is still suspended would race the orchestrator's
		// component teardown/setup. Cancel the previous init before starting a new
		// one so only one initialization is in flight at a time.
		initializationJob?.cancel()
		initializationJob = launch {
			// The collection trigger is chosen from BOTH the battery tier and whether the user
			// wants location. A GPS trigger only runs when location is enabled AND the tier is
			// GPS-capable; otherwise the lightweight non-GPS trigger drives cycles so any
			// combination of Wi-Fi/cell/activity/step sources is still collected without GPS.
			val locationEnabled = trackingParamsRepository.data.first().locationEnabled
			val initialTier = batteryAwarePolicy.adjustForBattery(
				resolveInitialPolicyTier(
					isUserInitiated = isUserInitiated,
					isAmbient = isAmbient,
					locationEnabled = locationEnabled,
				),
			)
			controller.updatePolicyTier(initialTier)
			val useGpsTrigger = locationEnabled && initialTier.isGpsEnabled
			timerComponent = if (useGpsTrigger) {
				TrackerTimerManager.getSelected(this@TrackerService)
			} else {
				AmbientCollectionTrigger()
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

			if (timerComponent.hasRequiredPermissions(this@TrackerService)) {
				timerComponent.onEnable(this@TrackerService, this@TrackerService)
			} else {
				Reporter.report("Missing permissions for ${timerComponent.javaClass}")
				stopSelf()
			}
		}

		// User-initiated sessions should restart after process death to preserve tracking.
		// Auto-tracking sessions can be re-triggered by ActivityWatcherService.
		return if (isUserInitiated) START_STICKY else START_NOT_STICKY
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
		stopSelf()
	}

	override fun onUpdate(cycle: TrackingCycle): Job = launch(dispatchers.default) {
		wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
		try {
			orchestrator.onCycleUpdate(this@TrackerService, cycle)
		} catch (e: CancellationException) {
			throw e
		} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
			Reporter.report(e)
		} finally {
			wakeLock.release()
		}

		// Power-save check (Android concern, kept in service)
		if (!requireNotNull(sessionInfo).isInitiatedByUser && powerManager.isPowerSaveMode) {
			stopSelf()
		}
	}

	override fun onError(errorData: TrackerTimerErrorData) {
		when (errorData.severity) {
			TrackerTimerErrorSeverity.STOP_SERVICE -> stopSelf()
			TrackerTimerErrorSeverity.REPORT -> Reporter.report(errorData.internalMessage)
			TrackerTimerErrorSeverity.NOTIFY_USER -> orchestrator.notificationComponent.onError(
				this,
				errorData.messageRes
			)
			TrackerTimerErrorSeverity.WARNING -> Reporter.log(errorData.internalMessage)
		}
	}

	override fun onDestroy() {
		// Capture references before super.onDestroy() cancels the coroutine scope
		val timerRef = timerComponent
		val context: Context = this

		super.onDestroy()
		stopForeground(STOP_FOREGROUND_REMOVE)

		// Cancel lock observation job to prevent leaks
		lockObservationJob?.cancel()
		lockObservationJob = null

		// Fire-and-forget cleanup on an independent scope to avoid blocking the main thread.
		// CoreService.onDestroy() already cancelled our CoroutineScope, so we use a standalone
		// scope with a bounded lifetime to ensure cleanup completes without causing ANR.
		val cleanupScope = kotlinx.coroutines.CoroutineScope(
			dispatchers.default + kotlinx.coroutines.SupervisorJob()
		)
		cleanupScope.launch {
			try {
				val shutdownResult = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
					orchestrator.shutdown(context) { timerRef.onDisable(context) }
				}
				if (shutdownResult == null) {
					Reporter.log("Tracker shutdown cleanup timed out; enqueueing daily summary fallback")
					orchestrator.enqueueDailySummaryFallback(context)
				}
			} finally {
				orchestrator.resetMetadata()
				cleanupScope.cancel()
			}
		}

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

	companion object {
		const val ARG_IS_USER_INITIATED = TrackerServiceContract.ARG_IS_USER_INITIATED
		const val ARG_IS_AMBIENT = TrackerServiceContract.ARG_IS_AMBIENT
		private const val DEFAULT_IS_USER_INITIATED = false
	}
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
): PolicyTier = when {
	isUserInitiated -> PolicyTier.PRECISION
	isAmbient -> PolicyTier.AMBIENT
	locationEnabled -> PolicyTier.ACTIVE
	else -> PolicyTier.AMBIENT
}
