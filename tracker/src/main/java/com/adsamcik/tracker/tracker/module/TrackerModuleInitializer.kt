package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import com.adsamcik.tracker.tracker.locker.TrackerLocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Initializes tracker module
 */
@Suppress("unused")
class TrackerModuleInitializer : ModuleInitializer {
	override fun initialize(context: Context) {
		if (Process.isMainProcess(context)) {
			// Use a lightweight module scope tied to app process; caller holds no reference so rely on process lifetime.
			CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
				BackgroundTrackingApi.initialize(context)
				TrackerLocker.initializeFromPersistence(context)
			}
		}
	}
}
