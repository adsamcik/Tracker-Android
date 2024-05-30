package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Initializes tracker module
 */
@Suppress("unused")
class TrackerModuleInitializer : ModuleInitializer {
	@OptIn(DelicateCoroutinesApi::class)
	override fun initialize(context: Context) {
		if (Process.isMainProcess(context)) {
			GlobalScope.launch(Dispatchers.Main) {
				BackgroundTrackingApi.initialize(context)
				TrackerLocker.initializeFromPersistence(context)
			}
		}
	}
}
