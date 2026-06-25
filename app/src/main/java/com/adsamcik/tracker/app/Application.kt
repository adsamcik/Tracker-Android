package com.adsamcik.tracker.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.app.event.PrecisionUpgradeDomainEventConsumer
import com.adsamcik.tracker.app.startup.ModuleInitializerCoordinator
import com.adsamcik.tracker.logger.CrashHandler
import com.adsamcik.tracker.logger.Logger
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.notification.GoalNotificationWorker
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.map.MapLibreInitializer
import com.adsamcik.tracker.network.NetworkGateway
import com.adsamcik.tracker.network.NetworkPolicyAggregator
import com.adsamcik.tracker.network.OkHttpBackedGateway
import com.adsamcik.tracker.game.goals.GoalResetScheduler
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import android.app.Application as AndroidApplication
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.preferences.store.PreferenceFlushLifecycleObserver
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.android.HiltAndroidApp
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
	lateinit var trackerServiceControllerProvider: Provider<TrackerServiceController>

	@Inject
	lateinit var lockManagerProvider: Provider<LockManager>

	@Inject
	lateinit var precisionUpgradeConsumerProvider: Provider<PrecisionUpgradeDomainEventConsumer>

	@Volatile
	var isStartupReady: Boolean = false
	private set

	private val deferredStartupStarted = AtomicBoolean(false)
	private val maintenanceStartupStarted = AtomicBoolean(false)
	
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
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
			Shortcuts.initializeShortcuts(this)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannels.prepareChannels(this)
		}
	}

	@MainThread
	private fun initializeImportantSingletons() {
		ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
			override fun onStart(owner: LifecycleOwner) {
				activityWatcherController.poke()
			}
		})
	}

	private fun initializeDatabaseMaintenance() {
		try {
			GoalResetScheduler.ensureScheduled(this)
		} catch (e: IllegalStateException) {
			Log.w("App", "Skipping GoalResetScheduler.ensureScheduled during unit tests: ${e.message}")
		}
		// Schedule periodic DB maintenance if WorkManager is available
		try {
			DatabaseMaintenanceWorker.schedule(this)
		} catch (e: IllegalStateException) {
			// In unit tests (Robolectric), WorkManager might not be initialized yet.
			Log.w("App", "Skipping DatabaseMaintenanceWorker.schedule during unit tests: ${e.message}")
		}
		// Ensure weekly auto-cleanup job is in sync with preference
		try {
			dataRetentionScheduler.initialize()
		} catch (e: IllegalStateException) {
			// In unit tests (Robolectric), WorkManager might not be initialized yet.
			Log.w("App", "Skipping DataRetentionScheduler.initialize during unit tests: ${e.message}")
		}
		// Schedule daily summary materialization (stats rearchitecture Phase 3)
		try {
			DailySummaryMaterializationWorker.schedule(this)
		} catch (e: IllegalStateException) {
			// In unit tests (Robolectric), WorkManager might not be initialized yet.
			Log.w("App", "Skipping DailySummaryMaterializationWorker.schedule during unit tests: ${e.message}")
		}
		// Schedule smart goal notification checks every 2 hours
		try {
			GoalNotificationWorker.schedule(this)
		} catch (e: IllegalStateException) {
			Log.w("App", "Skipping GoalNotificationWorker.schedule during unit tests: ${e.message}")
		}
	}

	/**
	 * Phase 1: resolve lightweight singletons (no DB access).
	 * Eagerly touches Hilt-provided singletons so they are ready before UI needs them.
	 */
	@WorkerThread
	private fun warmUp() {
		trackerServiceControllerProvider.get()
		lockManagerProvider.get()
	}

	private fun startBackgroundStartup() {
		appScope.launch(dispatchers.io) {
			try {
				Reporter.initialize(this@Application)
				Logger.initialize(this@Application)
				CrashHandler(this@Application).initialize()
				logPreviousExitReason()
				if (!isRobolectricUnitTest()) {
					initializeModules()
				}
			} catch (t: Throwable) {
				Log.e("App", "Background startup initialization failed", t)
			} finally {
				isStartupReady = true
			}
		}
	}

	private fun isRobolectricUnitTest(): Boolean = Build.FINGERPRINT == "robolectric"

	/**
	 * Logs the reason the previous process instance exited (Android 10+/API 30) so abnormal
	 * terminations — low-memory kills, OEM/SIGKILL, ANRs, native crashes — become observable
	 * in crash reports. This is the foundation for crash-informed recovery (e.g. draining the
	 * durable signal buffer after an abnormal kill) and for respecting a user-requested
	 * force-stop (REASON_USER_REQUESTED) instead of auto-restarting tracking.
	 */
	@WorkerThread
	private fun logPreviousExitReason() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
		try {
			val activityManager = getSystemService(ActivityManager::class.java) ?: return
			val exitInfo = activityManager
				.getHistoricalProcessExitReasons(packageName, 0, 1)
				.firstOrNull() ?: return

			val reasonLabel = when (exitInfo.reason) {
				ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
				ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
				ApplicationExitInfo.REASON_CRASH -> "CRASH"
				ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
				ApplicationExitInfo.REASON_ANR -> "ANR"
				ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
				ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
				ApplicationExitInfo.REASON_OTHER -> "OTHER"
				else -> "reason=${exitInfo.reason}"
			}

			val isAbnormal = when (exitInfo.reason) {
				ApplicationExitInfo.REASON_LOW_MEMORY,
				ApplicationExitInfo.REASON_SIGNALED,
				ApplicationExitInfo.REASON_CRASH,
				ApplicationExitInfo.REASON_CRASH_NATIVE,
				ApplicationExitInfo.REASON_ANR -> true
				else -> false
			}

			val message = "Previous process exit: $reasonLabel (status=${exitInfo.status})"
			if (isAbnormal) Reporter.w("App", message) else Reporter.log(message)
		} catch (e: RuntimeException) {
			// Defensive: getHistoricalProcessExitReasons can throw on some OEM builds.
			Reporter.report(e)
		}
	}

	fun startDeferredStartupIfNeeded() {
		if (!deferredStartupStarted.compareAndSet(false, true)) return

		appScope.launch(dispatchers.io) {
			try {
				coroutineScope {
					launch { warmUp() }
				}
				initializeClasses()
				initializeFeatures()
			} catch (t: Throwable) {
				Log.e("App", "Deferred startup initialization failed", t)
			}
		}
	}

	fun startMaintenanceStartupIfNeeded() {
		if (!maintenanceStartupStarted.compareAndSet(false, true)) return

		appScope.launch(dispatchers.io) {
			try {
				initializeDatabaseMaintenance()
			} catch (t: Throwable) {
				Log.e("App", "Maintenance startup initialization failed", t)
			}
		}
	}

	@WorkerThread
	private suspend fun initializeFeatures() {
		// Activities
		activityWatcherController.poke()
		
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

	private fun enableStrictMode() {
		if (BuildConfig.DEBUG) {
			android.os.StrictMode.setThreadPolicy(
				android.os.StrictMode.ThreadPolicy.Builder()
					.detectAll()
					.penaltyLog()
					.build()
			)
			android.os.StrictMode.setVmPolicy(
				android.os.StrictMode.VmPolicy.Builder()
					.detectLeakedSqlLiteObjects()
					.detectLeakedClosableObjects()
					.penaltyLog()
					.build()
			)
		}
	}

	override fun onCreate() {
		super.onCreate()
		enableStrictMode()

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
	}

}
