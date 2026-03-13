package com.adsamcik.tracker.app

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.logger.CrashHandler
import com.adsamcik.tracker.logger.Logger
import android.util.Log
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.points.PointsInitializer
import com.adsamcik.tracker.maintenance.DataRetentionScheduler
import com.adsamcik.tracker.map.MapLibreInitializer
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.tracker.service.ActivityWatcherServiceController
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.activity.ActivityModuleInitializer
import com.adsamcik.tracker.tracker.module.TrackerModuleInitializer
import com.adsamcik.tracker.game.GameModuleInitializer
import android.app.Application as AndroidApplication
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ActiveChallengesProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.DailySummaryProvider
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.preferences.store.PreferenceFlushLifecycleObserver
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import javax.inject.Inject


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

	@Volatile
	var isStartupReady: Boolean = false
		private set
	
	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	/**
	 * Entry point for eagerly resolving Hilt singletons during background startup.
	 * Preserves two-phase warm-up: lightweight singletons first, DB-touching providers second.
	 */
	@EntryPoint
	@InstallIn(SingletonComponent::class)
	interface WarmUpEntryPoint {
		fun trackerServiceController(): TrackerServiceController
		fun lockManager(): LockManager
		fun dailySummaryProvider(): DailySummaryProvider
		fun dailyPointsProvider(): DailyPointsProvider
		fun goalProgressProvider(): GoalProgressProvider
		fun activeChallengesProvider(): ActiveChallengesProvider
		fun exportAutomationController(): ExportAutomationController
	}

	companion object

	@SuppressLint("DefaultLocale")
	@WorkerThread
	private fun initializeModules() {
		// Static modules: directly initialize known initializers instead of reflection
		val initializers: List<ModuleInitializer> = listOf(
			ActivityModuleInitializer(),
			TrackerModuleInitializer(),
			GameModuleInitializer(),
		)
		initializers.forEach { it.initialize(this) }
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
	}

	@WorkerThread
	private fun initializeWorkManager() {
		try {
			WorkManager.initialize(this, workManagerConfiguration)
		} catch (e: IllegalStateException) {
			Log.w("App", "WorkManager already initialized: ${e.message}")
		}
	}

	/**
	 * Phase 1: resolve lightweight singletons (no DB access).
	 * Eagerly touches Hilt-provided singletons so they are ready before UI needs them.
	 */
	@WorkerThread
	private fun warmUp() {
		val ep = EntryPointAccessors.fromApplication(this, WarmUpEntryPoint::class.java)
		ep.trackerServiceController()
		ep.lockManager()
	}

	/**
	 * Phase 2: resolve DB-touching providers (lazy DB init on first query).
	 */
	@WorkerThread
	private fun warmUpDeferred() {
		val ep = EntryPointAccessors.fromApplication(this, WarmUpEntryPoint::class.java)
		ep.dailySummaryProvider()
		ep.dailyPointsProvider()
		ep.goalProgressProvider()
		ep.activeChallengesProvider()
	}

	private fun startBackgroundStartup() {
		appScope.launch(dispatchers.io) {
			try {
				Reporter.initialize(this@Application)
				Logger.initialize(this@Application)
				CrashHandler(this@Application).initialize()

				coroutineScope {
					launch { preloadNativeLibraries() }
					launch { MapLibreInitializer.initialize(this@Application) }
					launch { initializeWorkManager() }
					launch { warmUp() }
				}
			} catch (t: Throwable) {
				Log.e("App", "Background startup initialization failed", t)
			} finally {
				isStartupReady = true
			}

			launch(dispatchers.io) {
				try {
					warmUpDeferred()
					initializeClasses()
					initializeModules()
					initializeFeatures()
					initializeDatabaseMaintenance()
				} catch (t: Throwable) {
					Log.e("App", "Deferred startup initialization failed", t)
				}
			}
		}
	}

	/**
	 * Pre-load heavy native libraries on a background thread so they don't
	 * block the main thread when the corresponding UI first renders.
	 * MapLibre's native lib (~5s load on emulator) is the primary culprit.
	 */
	@WorkerThread
	private fun preloadNativeLibraries() {
		try {
			System.loadLibrary("maplibre")
		} catch (e: UnsatisfiedLinkError) {
			Log.w("App", "MapLibre native library not available: ${e.message}")
		}
	}

	@WorkerThread
	private suspend fun initializeFeatures() {
		// Points
		PointsInitializer().initialize(this)

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
		val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
			this,
			PrecisionUpgradeEntryPoint::class.java,
		)
		entryPoint.precisionUpgradeConsumer().processUnconsumed()
	}

	@dagger.hilt.EntryPoint
	@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
	interface PrecisionUpgradeEntryPoint {
		fun precisionUpgradeConsumer(): com.adsamcik.tracker.app.event.PrecisionUpgradeDomainEventConsumer
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
