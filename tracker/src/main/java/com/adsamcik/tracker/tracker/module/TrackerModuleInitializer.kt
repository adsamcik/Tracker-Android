package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.common.Process
import com.adsamcik.tracker.common.module.ModuleInitializer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("unused")
class TrackerModuleInitializer : ModuleInitializer {
	override fun initialize(context: Context) {
		if (Process.isMainProcess(context)) {
			GlobalScope.launch(Dispatchers.Main) {
				BackgroundTrackingApi.initialize(context)
				TrackerLocker.initializeFromPersistence(context)
			}
		}
	}
}
