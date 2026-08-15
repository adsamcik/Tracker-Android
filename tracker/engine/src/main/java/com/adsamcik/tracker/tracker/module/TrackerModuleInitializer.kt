package com.adsamcik.tracker.tracker.module

import android.content.Context
import com.adsamcik.tracker.shared.base.Process
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import com.adsamcik.tracker.tracker.api.BackgroundTrackingApi
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.resilience.TrackingStartupGuard
import com.adsamcik.tracker.tracker.source.coordinator.SourcePipelineRecovery
import com.adsamcik.tracker.diagnostics.TrackerTraceboxTemplates
import dev.tracebox.Tracebox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
	private val sourcePipelineRecovery: SourcePipelineRecovery,
) : ModuleInitializer {
	override val priority: Int = 20

	override fun initialize() {
		if (!Process.isMainProcess(context)) return
		if (trackingStartupGuard.isAutoRecoverySuppressed(context)) return

		applicationScope.launch {
			try {
				BackgroundTrackingApi.initialize(context)
				lockManager.initializeFromPersistence(context)
				val recovery = sourcePipelineRecovery.drainCommittedWork()
				if (recovery.drain !is com.adsamcik.tracker.tracker.source.coordinator.CoordinatorDrainResult.Complete) {
					Tracebox.log.warn(TrackerTraceboxTemplates.STARTUP_SOURCE_RECOVERY_DEFERRED)
				}
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Throwable) {
				Tracebox.log.error(error, TrackerTraceboxTemplates.TRACKER_MODULE_INITIALIZATION_FAILED)
			}
		}
	}
}
