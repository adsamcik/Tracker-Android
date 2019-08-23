package com.adsamcik.tracker.app

import android.os.Build
import androidx.annotation.AnyThread
import com.adsamcik.tracker.activity.ActivityModuleInitializer
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
	@AnyThread
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

	@AnyThread
	private fun initializeClasses() {
		if (Build.VERSION.SDK_INT >= 25) Shortcuts.initializeShortcuts(this)
		if (Build.VERSION.SDK_INT >= 26) NotificationChannels.prepareChannels(this)
	}

	override fun onCreate() {
		super.onCreate()

		GlobalScope.launch(Dispatchers.Default) {
			initializeClasses()
			initializeModules()
		}
	}

}

