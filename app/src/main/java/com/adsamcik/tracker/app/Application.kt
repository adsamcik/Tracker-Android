package com.adsamcik.tracker.app

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.adsamcik.tracker.BuildConfig
import com.adsamcik.tracker.logger.CrashHandler
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.points.PointsInitializer
import com.adsamcik.tracker.maintenance.DataRetentionWorker
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.activity.ActivityModuleInitializer
import com.adsamcik.tracker.tracker.module.TrackerModuleInitializer
import com.adsamcik.tracker.game.GameModuleInitializer
import android.app.Application as AndroidApplication
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.*
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.SystemClock
import javax.inject.Inject


/**
 * Main application
 * 
 * Composition Root Pattern (per copilot-instructions Section 16A):
 * - Initializes AppGraph as the single source of truth for dependencies
 * - Provides stable abstractions (Clock, DispatchersProvider, CoroutineScope)
 * - Enables test injection via Application subclassing
 * 
 * Hilt Integration:
 * - @HiltAndroidApp enables DI for Android components (Services, Workers, Receivers)
 * - AppGraph provides dependencies to Hilt modules (hybrid approach)
 * - Implements Configuration.Provider to inject HiltWorkerFactory for Workers
 */
@HiltAndroidApp
@Suppress("unused")
class Application : AndroidApplication(), Configuration.Provider {

	@Inject
	lateinit var workerFactory: HiltWorkerFactory

	// Core abstractions (exposed for rare direct access; prefer AppGraph)
	lateinit var dispatchers: DispatchersProvider
		private set

	lateinit var clock: Clock
		private set

	lateinit var appScope: CoroutineScope
		private set

	// Primary composition root - inject this into activities/services needing dependencies
	lateinit var appGraph: AppGraph
		private set
	
	override val workManagerConfiguration: Configuration
		get() = Configuration.Builder()
			.setWorkerFactory(workerFactory)
			.build()

	companion object {
		/**
		 * Global application instance.
		 * Used sparingly for backward compatibility with static access patterns.
		 * 
		 * @deprecated Prefer constructor injection via AppGraph when possible.
		 */
		lateinit var instance: Application
			private set
	}

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
		Reporter.initialize(this)
		Logger.initialize(this)
		CrashHandler(this).initialize()
	}

	@MainThread
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
			DataRetentionWorker.initialize(this)
		} catch (e: IllegalStateException) {
			// In unit tests (Robolectric), WorkManager might not be initialized yet.
			// Tests that need it will initialize WorkManager manually.
			Log.w("App", "Skipping DataRetentionWorker.initialize during unit tests: ${e.message}")
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
	private fun initializeFeatures() {
		// Points
		PointsInitializer().initialize(this)

		// Activities
		ActivityWatcherService.poke(this)
		
		// Precision upgrade — consume domain events for upgrade prompt logic
		initializePrecisionUpgradeConsumer()
	}
	
	/**
	 * Initializes the precision-upgrade domain event consumer.
	 * Processes any unconsumed SessionEnded events on startup (crash recovery).
	 */
	@WorkerThread
	private fun initializePrecisionUpgradeConsumer() {
		val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
			this,
			PrecisionUpgradeEntryPoint::class.java,
		)
		kotlinx.coroutines.runBlocking {
			entryPoint.precisionUpgradeConsumer().processUnconsumed()
		}
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

		instance = this
		initializeImportantSingletons()

		// Initialize core abstractions for composition root
		dispatchers = DefaultDispatchersProvider
		clock = SystemClock
		appScope = CoroutineScope(SupervisorJob() + dispatchers.default)
		
		// Build composition root with all dependencies
		appGraph = AppGraph.create(dispatchers, clock, appScope)
		appGraph.initialize(this)
		// Warm up export automation so plan observation/scheduling starts immediately
		appGraph.exportAutomationController

		// Preference observers must be registered on main thread
		initializeDatabaseMaintenance()

		appScope.launch {
			initializeClasses()
			initializeModules()
			initializeFeatures()
		}
	}

}

