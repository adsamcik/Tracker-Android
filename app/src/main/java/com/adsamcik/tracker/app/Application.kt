package com.adsamcik.tracker.app

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.ProcessLifecycleOwner
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.points.PointsInitializer
import com.adsamcik.tracker.shared.base.module.ModuleClassLoader
import com.adsamcik.tracker.shared.base.module.ModuleInitializer
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.utils.style.StyleLifecycleObserver
import com.adsamcik.tracker.tracker.service.ActivityWatcherService
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.google.android.play.core.splitcompat.SplitCompatApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*

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
		val activeModules = ModuleClassLoader.getEnabledModuleNames(this)

		activeModules.forEach { moduleName ->
			try {
				val initializer = ModuleClassLoader.loadClass<ModuleInitializer>(
						moduleName = moduleName,
						className = "${moduleName.capitalize(Locale.getDefault())}${ModuleInitializer::class.java.simpleName}"
				)
				initializer.newInstance().initialize(this)
			} catch (e: ClassNotFoundException) {
				//it's fine, do nothing
			}
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
	}

	@WorkerThread
	private fun initializeDatabaseMaintenance() {
		DatabaseMaintenanceWorker.schedule(this)
	}

	@WorkerThread
	private fun initializeFeatures() {
		// Points
		PointsInitializer().initialize(this)

		// Activities
		ActivityWatcherService.poke(this)
	}

	override fun onCreate() {
		super.onCreate()
		initializeImportantSingletons()

		GlobalScope.launch(Dispatchers.Default) {
			initializeClasses()
			initializeModules()
			initializeDatabaseMaintenance()
			initializeFeatures()
		}

		setupLifecycleListener()
	}

	private fun setupLifecycleListener() {
		ProcessLifecycleOwner.get().lifecycle.addObserver(styleObserver)
	}

}

