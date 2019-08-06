package com.adsamcik.signalcollector.tracker

import android.content.Context
import androidx.annotation.MainThread
import com.adsamcik.signalcollector.common.Process
import com.adsamcik.signalcollector.common.module.ModuleInitializer
import com.adsamcik.signalcollector.tracker.api.BackgroundTrackingApi
import com.adsamcik.signalcollector.tracker.locker.TrackerLocker
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