package com.adsamcik.tracker.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application as AndroidApplication
import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.adsamcik.tracker.app.event.PrecisionUpgradeDomainEventConsumer
import com.adsamcik.tracker.app.startup.ModuleInitializerCoordinator
import com.adsamcik.tracker.app.startup.LegacyDatabaseStartupResult
import com.adsamcik.tracker.app.startup.LegacyDatabaseUpgradeCoordinator
import com.adsamcik.tracker.app.tracebox.AndroidTrackerRuntimeMeasurementSource
import com.adsamcik.tracker.app.tracebox.TrackerRuntimeMeasurements
import com.adsamcik.tracker.app.tracebox.TrackerTraceboxRuntime
import com.adsamcik.tracker.app.tracebox.currentTrackerProcessName
import com.adsamcik.tracker.app.tracebox.isTraceboxHandlerProcessName
import com.adsamcik.tracker.app.tracebox.isTrackerMainProcessName
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.notification.GoalNotificationWorker
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.map.MapLibreInitializer
import com.adsamcik.tracker.network.NetworkGateway
import com.adsamcik.tracker.network.NetworkPolicyAggregator
import com.adsamcik.tracker.network.OkHttpBackedGateway
import com.adsamcik.tracker.game.goals.GoalResetScheduler
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.extension.ForegroundLocationCapability
import com.adsamcik.tracker.shared.base.extension.trackingPermissionCapabilities
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.store.PreferenceFlushLifecycleObserver
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.resilience.PreviousExitRecoveryAction
import com.adsamcik.tracker.tracker.resilience.PreviousExitRecoveryCoordinator
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import dagger.hilt.android.HiltAndroidApp
import dev.tracebox.Tracebox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider


/**
 * Main application
 * 
 * Hilt Integration:
 * - @HiltAndroidApp enables DI for all Android components
 * - All app-scoped services provided via Hilt SingletonComponent
 * - Implements Configuration.Provider to inject HiltWorkerFactory for Workers
 * - Startup warm-up eagerly touches Hilt singletons on a background thread
 */
@HiltAndroidApp
@Suppress("unused")
class Application : AndroidApplication(), Configuration.Provider {

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	@Inject
	lateinit var dispatchers: DispatchersProvider

	@Inject
	@ApplicationScope
	lateinit var appScope: CoroutineScope

	@Inject
	lateinit var dataRetentionScheduler: DataRetentionScheduler

	@Inject
	lateinit var activityWatcherController: ActivityWatcherServiceController

	@Inject
	lateinit var moduleInitializerCoordinator: ModuleInitializerCoordinator

	@Inject
	lateinit var legacyDatabaseUpgradeCoordinator: LegacyDatabaseUpgradeCoordinator

	@Inject
	lateinit var networkGateway: NetworkGateway

	/**
	 * Injected eagerly so its `init {}` block subscribes every Hilt-registered
	 * [com.adsamcik.tracker.network.NetworkPolicyContributor] to the
	 * [NetworkGateway] at process start. Without this `lateinit` field there
	 * would be no live subscription until some other component asked Hilt for
	 * the aggregator, leaving the gateway at its deny-all default and
	 * potentially racing UI that expects the policy already mirrored.
	 */
	@Suppress("unused")
	@Inject
	lateinit var networkPolicyAggregator: NetworkPolicyAggregator

	@Inject
	lateinit var trackerStateReaderProvider: Provider<TrackerStateReader>

	@Inject
	lateinit var lockManagerProvider: Provider<LockManager>

	@Inject
	lateinit var precisionUpgradeConsumerProvider: Provider<PrecisionUpgradeDomainEventConsumer>

	@Inject
	lateinit var previousExitRecoveryCoordinator: PreviousExitRecoveryCoordinator

	@Inject
	lateinit var trackingStartupGuard: TrackingStartupGuard

	@Volatile
	var isStartupReady: Boolean = false
	private set
	private val startupReconciliationCompletion = CompletableDeferred<Unit>()

	private val deferredStartupStarted = AtomicBoolean(false)
	private val maintenanceStartupStarted = AtomicBoolean(false)

	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(base)
		if (isRobolectricUnitTest()) return
		val processName = currentTrackerProcessName(base)
		if (isTraceboxHandlerProcessName(processName, packageName)) return
		if (isTrackerMainProcessName(processName, packageName)) {
			// Providers are created after Application attachment but before onCreate. Installing
			// here makes Tracebox the crash owner for that startup interval as well.
			TrackerTraceboxRuntime.install(this)
		}
	}
	
	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	@WorkerThread
	private fun initializeModules() {
		moduleInitializerCoordinator.initializeAll()
	}

	@WorkerThread
	private fun initializeClasses() {
		Shortcuts.initializeShortcuts(this)
		NotificationChannels.prepareChannels(this)
	}

	@MainThread
	private fun initializeImportantSingletons() {
		ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) {
				if (!startupReconciliationCompletion.isCompleted) return
				if (!legacyDatabaseUpgradeCoordinator.isReady()) return
				if (trackingStartupGuard.isAutoRecoverySuppressed(this@Application)) return
				activityWatcherController.poke()
				reconcilePersistedLocationPrecision()
				// Reconcile automatic detection with the current ACTIVITY_RECOGNITION permission:
				// disables detection if the permission was revoked while backgrounded, and re-arms it
				// if the user re-granted it (e.g. returning from system settings).
				BackgroundTrackingApi.revalidatePermissions(this@Application)
			}
		})
	}

	private fun reconcilePersistedLocationPrecision() {
		val precision = when (trackingPermissionCapabilities().foregroundLocation) {
			ForegroundLocationCapability.PRECISE -> "PRECISE"
			ForegroundLocationCapability.APPROXIMATE -> "APPROXIMATE"
			else -> return
		}
		Preferences(this).edit {
			setString(
				com.adsamcik.tracker.shared.preferences.R.string.settings_location_precision_key,
				precision,
			)
		}
	}

	private fun initializeDatabaseMaintenance() {
		val schedulingFailure = listOfNotNull(
			maintenanceSchedulingFailure { GoalResetScheduler.ensureScheduled(this) },
			maintenanceSchedulingFailure { DatabaseMaintenanceWorker.schedule(this) },
			maintenanceSchedulingFailure { dataRetentionScheduler.initialize() },
			maintenanceSchedulingFailure { DailySummaryMaterializationWorker.schedule(this) },
			maintenanceSchedulingFailure { GoalNotificationWorker.schedule(this) },
		).firstOrNull()
		schedulingFailure?.let { error ->
			Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
		}
	}

	private fun maintenanceSchedulingFailure(schedule: () -> Unit): IllegalStateException? = try {
		schedule()
		null
	} catch (error: IllegalStateException) {
		error
	}

	/**
	 * Phase 1: resolve lightweight singletons (no DB access).
	 * Eagerly touches Hilt-provided singletons so they are ready before UI needs them.
	 */
	@WorkerThread
	private fun warmUp() {
		trackerStateReaderProvider.get()
		lockManagerProvider.get()
	}

	private fun startBackgroundStartup() {
		appScope.launch(dispatchers.io) {
			try {
				val recoveryAction = determineStartupRecoveryAction()
				if (recoveryAction is ApplicationStartupRecoveryAction.ConfirmedForceStop) {
					// Set the process guard before any legacy inspection or target Room open.
					trackingStartupGuard.suppressAutoRecoveryForCurrentProcess()
				}
				when (val legacy = legacyDatabaseUpgradeCoordinator.ensureReady()) {
					LegacyDatabaseStartupResult.Ready -> Unit
					is LegacyDatabaseStartupResult.Failed -> {
						Tracebox.log.error(TrackerTraceboxTemplates.LEGACY_DATABASE_IMPORT_FAILED)
						return@launch
					}
				}
				applyStartupRecovery(recoveryAction)
				Tracebox.log.info(TrackerTraceboxTemplates.APPLICATION_STARTUP_RECONCILED)
				startupReconciliationCompletion.complete(Unit)
				if (!isRobolectricUnitTest()) {
					initializeModules()
				}
			} catch (error: Throwable) {
				Tracebox.log.error(error, TrackerTraceboxTemplates.APPLICATION_INITIALIZATION_FAILED)
			} finally {
				isStartupReady = true
				startupReconciliationCompletion.complete(Unit)
			}
		}
	}

	private fun isRobolectricUnitTest(): Boolean = Build.FINGERPRINT == "robolectric"

	@WorkerThread
	private fun determineStartupRecoveryAction(): ApplicationStartupRecoveryAction {
		val mainProcessExit = previousMainProcessExit()
		return applicationStartupRecoveryAction(
			confirmedForceStop = trackingStartupGuard.wasForceStopped(this),
			mainProcessExit = mainProcessExit,
			fallbackTimestampMs = System.currentTimeMillis(),
		)
	}

	@WorkerThread
	private suspend fun applyStartupRecovery(
		action: ApplicationStartupRecoveryAction,
	): PreviousExitRecoveryAction {
		val result = when (action) {
			is ApplicationStartupRecoveryAction.ConfirmedForceStop -> {
				previousExitRecoveryCoordinator.suppressAfterForceStop(action.completedAtMs)
				PreviousExitRecoveryAction.NONE
			}
			is ApplicationStartupRecoveryAction.PreviousExit ->
				previousExitRecoveryCoordinator.handle(action.reason)
			ApplicationStartupRecoveryAction.None -> PreviousExitRecoveryAction.NONE
		}
		// A force-stop suppresses new collection, not completion of WAL rows that were already
		// durably admitted. This also covers missing/ambiguous historical exit records.
		previousExitRecoveryCoordinator.enqueueDrainIfPending()
		return result
	}

	@WorkerThread
	private fun previousMainProcessExit(): HistoricalProcessExit? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
		return try {
			val activityManager = getSystemService(ActivityManager::class.java) ?: return null
			val records = activityManager
				.getHistoricalProcessExitReasons(packageName, 0, HISTORICAL_EXIT_RECORD_LIMIT)
				.map { exitInfo ->
					HistoricalProcessExit(
						processName = exitInfo.processName,
						reason = exitInfo.reason,
						timestampMs = exitInfo.timestamp,
					)
				}
			mostRecentMainProcessExit(records, packageName)
		} catch (_: RuntimeException) {
			null
		}
	}

	fun startDeferredStartupIfNeeded() {
		if (!deferredStartupStarted.compareAndSet(false, true)) return

		appScope.launch(dispatchers.io) {
			awaitStartupReconciliation()
			try {
				coroutineScope {
					launch { warmUp() }
				}
				initializeClasses()
				initializeFeatures()
			} catch (error: Throwable) {
				Tracebox.log.error(error, TrackerTraceboxTemplates.APPLICATION_INITIALIZATION_FAILED)
			}
		}
	}

	fun startMaintenanceStartupIfNeeded() {
		if (!maintenanceStartupStarted.compareAndSet(false, true)) return

		appScope.launch(dispatchers.io) {
			awaitStartupReconciliation()
			try {
				initializeDatabaseMaintenance()
			} catch (error: Throwable) {
				Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
			}
		}
	}

	internal suspend fun awaitStartupReconciliation() {
		startupReconciliationCompletion.await()
	}

	@WorkerThread
	private suspend fun initializeFeatures() {
		// Activities
		if (!trackingStartupGuard.isAutoRecoverySuppressed(this)) {
			activityWatcherController.poke()
		}
		
		// Precision upgrade — consume domain events for upgrade prompt logic
		initializePrecisionUpgradeConsumer()
	}
	
	/**
	 * Initializes the precision-upgrade domain event consumer.
	 * Processes any unconsumed SessionEnded events on startup (crash recovery).
	 */
	@WorkerThread
	private suspend fun initializePrecisionUpgradeConsumer() {
		precisionUpgradeConsumerProvider.get().processUnconsumed()
	}

	@SuppressLint("MissingSuperCall")
	override fun onCreate() {
		val processName = currentTrackerProcessName(this)
		if (isTraceboxHandlerProcessName(processName, packageName)) {
			// android.app.Application.onCreate is empty. Skipping the generated Hilt super call
			// keeps Tracker's dependency graph and startup work out of Tracebox's private handler.
			return
		}
		if (!isRobolectricUnitTest() && isTrackerMainProcessName(processName, packageName)) {
			TrackerTraceboxRuntime.install(this)
		}
		super.onCreate()
		Tracebox.log.info(TrackerTraceboxTemplates.APPLICATION_PROCESS_STARTED)
		val measurements = TrackerRuntimeMeasurements(
			logger = Tracebox.log,
			source = AndroidTrackerRuntimeMeasurementSource(this),
			scope = appScope,
			dispatcher = dispatchers.io,
		)
		ProcessLifecycleOwner.get().lifecycle.addObserver(measurements)

		// Wire MapLibre's HTTP through the project NetworkGateway so tile/style/sprite
		// fetches share the kill switch + allowlist + rate-limit interceptors that
		// every other future egress consumer uses. Idempotent process-global setter;
		// safe to call before MapLibreInitializer.initialize() runs in any feature.
		// Skips silently in unit tests where the gateway isn't OkHttp-backed (Fake).
		(networkGateway as? OkHttpBackedGateway)?.okHttpCallFactory()?.let { factory ->
			MapLibreInitializer.setHttpCallFactory(factory)
		}

		initializeImportantSingletons()

		// Flush pending DataStore writes when the app goes to background
		ProcessLifecycleOwner.get().lifecycle.addObserver(
			PreferenceFlushLifecycleObserver(this, appScope)
		)
		
		startBackgroundStartup()
		measurements.recordStartup()
	}

	private companion object {
		const val HISTORICAL_EXIT_RECORD_LIMIT = 16
	}
}

internal data class HistoricalProcessExit(
	val processName: String?,
	val reason: Int,
	val timestampMs: Long,
)

internal fun mostRecentMainProcessExit(
	records: List<HistoricalProcessExit>,
	mainProcessName: String,
): HistoricalProcessExit? = records
	.asSequence()
	.filter { it.processName == mainProcessName }
	.maxByOrNull { it.timestampMs }

internal sealed interface ApplicationStartupRecoveryAction {
	data class ConfirmedForceStop(val completedAtMs: Long) : ApplicationStartupRecoveryAction
	data class PreviousExit(val reason: Int) : ApplicationStartupRecoveryAction
	data object None : ApplicationStartupRecoveryAction
}

internal fun applicationStartupRecoveryAction(
	confirmedForceStop: Boolean,
	mainProcessExit: HistoricalProcessExit?,
	fallbackTimestampMs: Long,
): ApplicationStartupRecoveryAction = when {
	confirmedForceStop -> ApplicationStartupRecoveryAction.ConfirmedForceStop(
		completedAtMs = mainProcessExit?.timestampMs ?: fallbackTimestampMs,
	)
	mainProcessExit != null -> ApplicationStartupRecoveryAction.PreviousExit(mainProcessExit.reason)
	else -> ApplicationStartupRecoveryAction.None
}
