package com.adsamcik.tracker.app

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.logger.CrashHandler
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.points.PointsInitializer
import com.adsamcik.tracker.maintenance.DataRetentionWorker
import com.adsamcik.tracker.shared.utils.module.ModuleClassLoader
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.shared.utils.style.StyleLifecycleObserver
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.google.android.play.core.splitcompat.SplitCompatApplication
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Main application
 */
@Suppress("unused")
@ExperimentalStdlibApi
class Application : SplitCompatApplication() {

	private val styleObserver = StyleLifecycleObserver(this)

	@SuppressLint("DefaultLocale")
	@WorkerThread
	private fun initializeModules() {
		ModuleClassLoader.invokeInEachActiveModule<ModuleInitializer>(this) {
			it.initialize(this)
		}
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
	}

	@WorkerThread
	private fun initializeFeatures() {
		// Points
		PointsInitializer().initialize(this)

		// Activities
		ActivityWatcherService.poke(this)
	}

	@OptIn(DelicateCoroutinesApi::class)
	override fun onCreate() {
		super.onCreate()
		initializeImportantSingletons()

	// Preference observers must be registered on main thread
	initializeDatabaseMaintenance()

	GlobalScope.launch(Dispatchers.Default) {
			initializeClasses()
			initializeModules()
			initializeFeatures()
		}

		setupLifecycleListener()
	}

	private fun setupLifecycleListener() {
		ProcessLifecycleOwner.get().lifecycle.addObserver(styleObserver)
	}

}

