package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Initializes tracker module
 */
class TrackerModuleInitializer @Inject constructor(
	@ApplicationContext private val context: Context,
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val lockManager: LockManager,
	private val trackingStartupGuard: TrackingStartupGuard,
) : ModuleInitializer {
	override val priority: Int = 20

	override fun initialize() {
		if (!Process.isMainProcess(context)) return
		if (trackingStartupGuard.isAutoRecoverySuppressed(context)) return

		applicationScope.launch {
			BackgroundTrackingApi.initialize(context)
			lockManager.initializeFromPersistence(context)
		}
	}
}
