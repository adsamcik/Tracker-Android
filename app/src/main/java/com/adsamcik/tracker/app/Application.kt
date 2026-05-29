package com.adsamcik.tracker.app

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
import com.adsamcik.tracker.game.goals.GoalResetScheduler
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import android.app.Application as AndroidApplication
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.ChallengeDatabaseFold
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
	lateinit var trackerServiceControllerProvider: Provider<TrackerServiceController>

	@Inject
	lateinit var lockManagerProvider: Provider<LockManager>

	@Inject
	lateinit var precisionUpgradeConsumerProvider: Provider<PrecisionUpgradeDomainEventConsumer>

	@Inject
	lateinit var challengeDatabaseFoldProvider: Provider<ChallengeDatabaseFold>

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
				challengeDatabaseFoldProvider.get().awaitComplete()
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

		initializeImportantSingletons()

		// Flush pending DataStore writes when the app goes to background
		ProcessLifecycleOwner.get().lifecycle.addObserver(
			PreferenceFlushLifecycleObserver(this, appScope)
		)
		
		startBackgroundStartup()
	}

}
