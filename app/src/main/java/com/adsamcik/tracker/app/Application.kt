package com.adsamcik.tracker.app

import android.annotation.SuppressLint
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
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.app.settings.PostDeletionAutomaticControlRestorer
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
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import com.adsamcik.tracker.shared.base.startup.TrackingStartupResult
import com.adsamcik.tracker.shared.base.startup.TrackingStartupStage
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.store.PreferenceFlushLifecycleObserver
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.permission.RuntimePermissionChange
import com.adsamcik.tracker.tracker.permission.RuntimePermissionReconciler
import com.adsamcik.tracker.tracker.permission.RuntimePermissionSnapshot
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import dagger.hilt.android.HiltAndroidApp
import dev.tracebox.Tracebox
import dev.tracebox.api.public
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
	lateinit var trackingStartupGuard: TrackingStartupGuard

	@Inject
	lateinit var trackingStartupGate: TrackingStartupGate

	@Inject
	lateinit var trackingStartupDeletionBarrier: TrackingStartupDeletionBarrier

	@Inject
	lateinit var runtimePermissionReconciler: RuntimePermissionReconciler

	@Inject
	lateinit var postDeletionAutomaticControlRestorer: PostDeletionAutomaticControlRestorer

	@Volatile
	var isStartupReady: Boolean = false
	private set
	private val startupReconciliationCompletion = CompletableDeferred<TrackingStartupResult>()

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
				appScope.launch(dispatchers.io) {
					try {
						// Process foreground is the only app-owned proof of an explicit launch. Record
						// intent now, but keep force-stop suppression closed until the tracker initializer
						// has observed durable startup Ready and consumed it.
						trackingStartupGuard.recordExplicitForegroundLaunch(this@Application)
						// Target Room must stay unopened until deletion, migration, prior-exit,
						// and provider-registration recovery have reached the startup gate.
						awaitStartupReconciliation()
						if (!trackingStartupGate.isReady) return@launch
						reconcileRuntimePermissionSnapshot(
							snapshot = RuntimePermissionSnapshot.capture(this@Application),
							reconcile = runtimePermissionReconciler::reconcile,
						) {
							reconcilePersistedLocationPrecision()
							withContext(dispatchers.main) {
								if (!trackingStartupGate.isReady) return@withContext
								if (trackingStartupGuard.isAutoRecoverySuppressed(this@Application)) {
									return@withContext
								}
								activityWatcherController.poke()
								// The durable permission fence now precedes any control re-registration.
								BackgroundTrackingApi.revalidatePermissions(this@Application)
							}
						}
					} catch (cancelled: CancellationException) {
						throw cancelled
					} catch (error: Throwable) {
						Tracebox.log.error(
							error,
							TrackerTraceboxTemplates.TRACKING_RUNTIME_PERMISSION_RECONCILIATION_FAILED,
						)
					}
				}
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
			// Retire the unsafe legacy six-hour empty-segment cleanup. Component-owned teardown
			// remains the only writer that may remove its preinserted session row.
			maintenanceSchedulingFailure { DatabaseMaintenanceWorker.cancel(this) },
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
			trackingStartupDeletionBarrier.openGenerations.collect {
				var terminalResult: TrackingStartupResult? = null
				try {
					terminalResult = driveTrackingStartup(
						reconcile = { reconcileTrackingStartup() },
						onRetryableVisible = ::publishStartupResolution,
					)
					when (val startup = terminalResult) {
						is TrackingStartupResult.Ready -> Unit
						is TrackingStartupResult.Blocked -> Tracebox.log.error(
							TrackerTraceboxTemplates.APPLICATION_STARTUP_BLOCKED,
							public(startup.stage.name),
							public(startup.failureCode),
						)
						is TrackingStartupResult.RetryableFailure -> Tracebox.log.error(
							TrackerTraceboxTemplates.APPLICATION_STARTUP_RETRYABLE_FAILURE,
							public(startup.stage.name),
							public(startup.failureCode),
						)
					}
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Throwable) {
					Tracebox.log.error(
						error,
						TrackerTraceboxTemplates.APPLICATION_INITIALIZATION_FAILED,
					)
					terminalResult = TrackingStartupResult.RetryableFailure(
						TrackingStartupStage.LIVE_V2,
						"APPLICATION_STARTUP_FAILED:${error.javaClass.simpleName}",
					)
				} finally {
					terminalResult?.let(::publishStartupResolution)
				}
			}
		}
	}

	private fun publishStartupResolution(result: TrackingStartupResult) {
		isStartupReady = true
		startupReconciliationCompletion.complete(result)
	}

	private fun isRobolectricUnitTest(): Boolean = Build.FINGERPRINT == "robolectric"

	@WorkerThread
	internal suspend fun reconcileTrackingStartup(
		retryFailedStorage: Boolean = false,
	): TrackingStartupResult = trackingStartupGate.reconcile(retryFailedStorage).also { result ->
		if (result is TrackingStartupResult.Ready) {
			// A permanently Blocked post-deletion worker deliberately does not poll. The explicit
			// repair path that makes startup Ready is its one same-process rearm signal.
			postDeletionAutomaticControlRestorer.onAuthoritativeStartupReady(
				trackingStartupGate.currentGeneration,
			)
			if (!isRobolectricUnitTest()) initializeModules()
		}
	}

	fun startDeferredStartupIfNeeded() {
		if (!deferredStartupStarted.compareAndSet(false, true)) return

		appScope.launch(dispatchers.io) {
			awaitStartupReconciliation()
			if (!trackingStartupGate.isReady) {
				deferredStartupStarted.set(false)
				return@launch
			}
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
			if (!trackingStartupGate.isReady) {
				maintenanceStartupStarted.set(false)
				return@launch
			}
			try {
				initializeDatabaseMaintenance()
			} catch (error: Throwable) {
				Tracebox.log.error(error, TrackerTraceboxTemplates.DATA_RETENTION_FAILED)
			}
		}
	}

	internal suspend fun awaitStartupReconciliation(): TrackingStartupResult =
		startupReconciliationCompletion.await()

	@WorkerThread
	private suspend fun initializeFeatures() {
		// Activities
		if (trackingStartupGate.isReady && !trackingStartupGuard.isAutoRecoverySuppressed(this)) {
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

}

/** Runs foreground repair only after any required durable permission fence returns. */
internal suspend fun reconcileRuntimePermissionSnapshot(
	snapshot: RuntimePermissionSnapshot,
	reconcile: suspend (RuntimePermissionSnapshot) -> RuntimePermissionChange?,
	afterDurableReconciliation: suspend () -> Unit,
): RuntimePermissionChange? {
	val change = reconcile(snapshot)
	afterDurableReconciliation()
	return change
}

/** The one process owner retries transient startup failures; permanent blocks reach recovery UI. */
internal suspend fun driveTrackingStartup(
	reconcile: suspend () -> TrackingStartupResult,
	waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
	onRetryableVisible: (TrackingStartupResult.RetryableFailure) -> Unit = {},
): TrackingStartupResult {
	var retryDelayMillis = STARTUP_RETRY_INITIAL_DELAY_MS
	var retryableFailures = 0
	var visibleFailurePublished = false
	while (true) {
		when (val result = reconcile()) {
			is TrackingStartupResult.Ready,
			is TrackingStartupResult.Blocked,
			-> return result
			is TrackingStartupResult.RetryableFailure -> {
				retryableFailures += 1
				if (!visibleFailurePublished &&
					retryableFailures >= STARTUP_RETRY_FAILURES_BEFORE_UI
				) {
					visibleFailurePublished = true
					onRetryableVisible(result)
				}
				waitBeforeRetry(retryDelayMillis)
				retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(STARTUP_RETRY_MAX_DELAY_MS)
			}
		}
	}
}

private const val STARTUP_RETRY_INITIAL_DELAY_MS = 500L
private const val STARTUP_RETRY_MAX_DELAY_MS = 30_000L
private const val STARTUP_RETRY_FAILURES_BEFORE_UI = 4

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

internal fun recentMainProcessExitCount(
	records: List<HistoricalProcessExit>,
	mainProcessName: String,
	reason: Int,
	nowMs: Long,
	windowMs: Long,
): Int {
	require(windowMs > 0L)
	val cutoffMs = (nowMs - windowMs).coerceAtLeast(0L)
	return records.count { exit ->
		exit.processName == mainProcessName &&
			exit.reason == reason &&
			exit.timestampMs in cutoffMs..nowMs
	}
}

internal sealed interface ApplicationStartupRecoveryAction {
	/** Positive ApplicationStartInfo.wasForceStopped evidence; available only on API 35+. */
	data class ConfirmedForceStop(val completedAtMs: Long) : ApplicationStartupRecoveryAction

	/**
	 * Historical process-exit evidence, including ambiguous user-requested exits on API 30-34.
	 * This action performs ordinary stale-session cleanup and never claims a force-stop.
	 */
	data class PreviousExit(
		val reason: Int,
		val completedAtMs: Long,
	) : ApplicationStartupRecoveryAction
	data object None : ApplicationStartupRecoveryAction
}

/** [confirmedForceStop] must be positive platform evidence, never an inferred exit reason. */
internal fun applicationStartupRecoveryAction(
	confirmedForceStop: Boolean,
	mainProcessExit: HistoricalProcessExit?,
	fallbackTimestampMs: Long,
): ApplicationStartupRecoveryAction = when {
	confirmedForceStop -> ApplicationStartupRecoveryAction.ConfirmedForceStop(
		completedAtMs = mainProcessExit?.timestampMs ?: fallbackTimestampMs,
	)
	mainProcessExit != null -> ApplicationStartupRecoveryAction.PreviousExit(
		reason = mainProcessExit.reason,
		completedAtMs = mainProcessExit.timestampMs,
	)
	else -> ApplicationStartupRecoveryAction.None
}
