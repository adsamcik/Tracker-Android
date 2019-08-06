package com.adsamcik.signalcollector.tracker

import android.content.Context
import com.adsamcik.signalcollector.common.Process
import com.adsamcik.signalcollector.common.module.ModuleInitializer
import com.adsamcik.signalcollector.tracker.api.BackgroundTrackingApi
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker

@Suppress("unused")
class TrackerModuleInitializer : ModuleInitializer {
	override fun initialize(context: Context) {
		if (Process.isMainProcess(context)) {
			BackgroundTrackingApi.initialize(context)
			TrackerLocker.initializeFromPersistence(context)
		}
	}
}