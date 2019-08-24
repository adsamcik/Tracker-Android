package com.adsamcik.tracker.app

import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.activity.ActivityModuleInitializer
import com.adsamcik.tracker.common.debug.Logger
import com.adsamcik.tracker.common.debug.Reporter
import com.adsamcik.tracker.common.module.ModuleInitializer
import com.adsamcik.tracker.module.Module
import com.adsamcik.tracker.notification.NotificationChannels
import com.adsamcik.tracker.tracker.TrackerModuleInitializer
import com.adsamcik.tracker.tracker.shortcut.Shortcuts
import com.google.android.play.core.splitcompat.SplitCompatApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("unused")
class Application : SplitCompatApplication() {
	@WorkerThread
	private fun initializeModules() {
		ActivityModuleInitializer().initialize(this)
		TrackerModuleInitializer().initialize(this)

		val activeModules = Module.getActiveModuleInfo(this)
		activeModules.forEach {
			try {
				val initializer = it.module.loadClass<ModuleInitializer>(
						"${it.module.moduleName.capitalize()}${ModuleInitializer::class.java.simpleName}"
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

	override fun onCreate() {
		super.onCreate()
		initializeImportantSingletons()

		GlobalScope.launch(Dispatchers.Default) {
			initializeClasses()
			initializeModules()
		}
	}

}

