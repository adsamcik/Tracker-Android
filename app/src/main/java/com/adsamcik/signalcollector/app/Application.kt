package com.adsamcik.signalcollector.app

import com.adsamcik.signalcollector.common.module.ModuleInitializer
import com.adsamcik.signalcollector.module.Module
import com.google.android.play.core.splitcompat.SplitCompatApplication

class Application : SplitCompatApplication() {
	private fun initializeModules() {
		val activeModules = Module.getActiveModuleInfo(this)
		activeModules.forEach {
			try {
				val initializer = it.module.loadClass<ModuleInitializer>("${it.module.moduleName.capitalize()}${ModuleInitializer::class.java.simpleName}")
				initializer.newInstance().initialize(this)
			} catch (e: ClassNotFoundException) {
				//it's fine, do nothing
			}
		}
	}

	override fun onCreate() {
		super.onCreate()

		initializeModules()
	}

}