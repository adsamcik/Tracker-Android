package com.adsamcik.tracker.tracker.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.extension.getSystemServiceTyped
import com.adsamcik.tracker.shared.base.service.CoreService
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.api.processor.SignalProcessor
import com.adsamcik.tracker.stats.api.repository.DomainEventRepository
import com.adsamcik.tracker.tracker.R
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.NoTimer
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorData
import com.adsamcik.tracker.tracker.component.TrackerTimerErrorSeverity
import com.adsamcik.tracker.tracker.component.TrackerTimerManager
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.component.trigger.AmbientCollectionTrigger
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import com.adsamcik.tracker.tracker.module.TrackerListenerManager
import com.adsamcik.tracker.tracker.notification.TrackerNotificationManager
import com.adsamcik.tracker.tracker.shortcut.ShortcutData
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

	private lateinit var orchestrator: TrackingOrchestrator

	private var lockObservationJob: Job? = null
	private var timerComponent: CollectionTriggerComponent = NoTimer()

	// Kept here for intent recovery and power-save check
	private var sessionInfo: TrackerSessionInfo? = null

	override fun onCreate() {
		super.onCreate()

		ensureForegroundStarted()

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
			appDatabase = appDatabase,
			trackingParamsRepository = trackingParamsRepository,
			trackerSettingsRepository = trackerSettingsRepository,
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
		ensureForegroundStarted()

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
		val initialTier = when {
			isUserInitiated -> PolicyTier.PRECISION
			isAmbient -> PolicyTier.AMBIENT
			else -> PolicyTier.AMBIENT
		}
		controller.updatePolicyTier(initialTier)

		// Select timer based on tier: AMBIENT uses lightweight handler, ACTIVE+ uses GPS-based timer
		if (initialTier == PolicyTier.AMBIENT) {
			timerComponent = AmbientCollectionTrigger()
		}

		controller.updateServiceRunning(true)

		this.sessionInfo = TrackerSessionInfo(isUserInitiated)
		controller.updateSessionInfo(this.sessionInfo)

		if (!isUserInitiated) {
			lockObservationJob = launch {
				lockManager.isLockedFlow.collect { isLocked ->
					if (isLocked) stopSelf()
				}
			}
		}

		ActivityWatcherService.poke(this, trackerRunning = true)

		launch {
			if (initialTier != PolicyTier.AMBIENT) {
				timerComponent = TrackerTimerManager.getSelected(this@TrackerService)
			}

			val timerAccessor = object : TrackerTierEscalationHandler.TimerAccessor {
				override fun get() = timerComponent
				override fun set(timer: CollectionTriggerComponent) {
					timerComponent = timer
				}
			}

			async(Dispatchers.Default) {
				orchestrator.initialize(
					context = this@TrackerService,
					isSessionUserInitiated = isUserInitiated,
					initialTier = initialTier,
					scope = this@TrackerService,
					timerReceiver = this@TrackerService,
					timerAccessor = timerAccessor,
				)
			}.await()

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

	private fun ensureForegroundStarted() {
		val notification = TrackerNotificationManager.getForegroundNotification(this)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(
				TrackerNotificationManager.NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
			)
		} else {
			startForeground(
				TrackerNotificationManager.NOTIFICATION_ID,
				notification
			)
		}
	}

	override fun onUpdate(cycle: TrackingCycle): Job = launch(Dispatchers.Default) {
		wakeLock.acquire(Time.SECOND_IN_MILLISECONDS * 10L)
		try {
			orchestrator.onCycleUpdate(this@TrackerService, cycle, this@TrackerService)
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
			Dispatchers.Default + kotlinx.coroutines.SupervisorJob()
		)
		cleanupScope.launch {
			try {
				kotlinx.coroutines.withTimeoutOrNull(4_000L) {
					orchestrator.shutdown(context) { timerRef.onDisable(context) }
				}
			} finally {
				orchestrator.resetMetadata()
				cleanupScope.cancel()
			}
		}

		ActivityWatcherService.poke(this@TrackerService, trackerRunning = false)

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
		const val ARG_IS_USER_INITIATED = "userInitiated"
		const val ARG_IS_AMBIENT = "isAmbient"
		private const val DEFAULT_IS_USER_INITIATED = false
	}
}
