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

		val foregroundStarted = ensureForegroundStarted()

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

		if (!foregroundStarted) {
			// startForeground failed (e.g. location permission revoked or a background
			// start was blocked). Stop cleanly instead of lingering as a started-but-not-
			// foreground service that the platform would later kill.
			stopSelf()
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		if (!ensureForegroundStarted()) {
			Reporter.w(
				"TrackerService",
				"Could not enter foreground (missing permission or blocked background start); stopping"
			)
			stopSelfResult(startId)
			return START_NOT_STICKY
		}

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

		// Determine initial tier from intent flags
		val rawTier = when {
			isUserInitiated -> PolicyTier.PRECISION
			isAmbient -> PolicyTier.AMBIENT
			else -> PolicyTier.AMBIENT
		}
		val initialTier = batteryAwarePolicy.adjustForBattery(rawTier)
		controller.updatePolicyTier(initialTier)

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
	 * Promotes the service to the foreground, choosing a foreground-service type that
	 * matches the permissions actually held:
	 *  - [ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION] when a location permission is
	 *    granted (GPS sessions), and
	 *  - [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] as a fallback so Wi-Fi/cell/
	 *    activity-only ("ambient") sessions can still run when location permission is absent.
	 *
	 * Starting a location-typed foreground service without a location permission throws a
	 * [SecurityException] on Android 14+, and any blocked background start throws
	 * `ForegroundServiceStartNotAllowedException` (an [IllegalStateException] subclass) on
	 * Android 12+. Both are caught so a failed start surfaces a user-visible notification and
	 * a clean stop instead of an uncaught crash or a silent no-op.
	 *
	 * @return true if the service is now in the foreground, false if it could not be started.
	 */
	private fun ensureForegroundStarted(): Boolean {
		TrackerNotificationChannels.ensureTrackingChannel(this)
		val notification = TrackerNotificationManager.getForegroundNotification(this)

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return tryStartForeground(notification, fgsType = null)
		}

		val preferredType = if (hasLocationPermission) {
			ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
		} else {
			ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
		}
		if (tryStartForeground(notification, preferredType)) return true

		// If the location type was rejected (e.g. permission revoked between the check and
		// the start call), fall back to the special-use type before giving up.
		if (preferredType != ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE &&
			tryStartForeground(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		) {
			return true
		}

		TrackerNotificationManager.postStartFailedNotification(this)
		return false
	}

	private fun tryStartForeground(notification: Notification, fgsType: Int?): Boolean {
		return try {
			if (fgsType != null) {
				startForeground(TrackerNotificationManager.NOTIFICATION_ID, notification, fgsType)
			} else {
				startForeground(TrackerNotificationManager.NOTIFICATION_ID, notification)
			}
			true
		} catch (e: SecurityException) {
			// Missing runtime permission for the requested FGS type (Android 14+).
			Reporter.report(e)
			false
		} catch (e: IllegalStateException) {
			// ForegroundServiceStartNotAllowedException (Android 12+) is an
			// IllegalStateException subclass; also covers an already-stopped service.
			Reporter.report(e)
			false
		}
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

	/**
	 * Android 15+ may time out a foreground service of a time-limited type. Stop cleanly so
	 * the platform does not raise a fatal `RemoteServiceException`; the normal [onDestroy]
	 * cleanup then flushes the durable signal buffer. Auto-tracking sessions can be
	 * re-triggered by [ActivityWatcherService]; user sessions end gracefully.
	 */
	override fun onTimeout(startId: Int, fgsType: Int) {
		Reporter.w("TrackerService", "Foreground service timed out (type=$fgsType); stopping")
		stopSelf(startId)
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
