package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerServiceController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for API classes to access TrackerServiceController and LockManager.
 * 
 * Used by:
 * - TrackerServiceApi: For session state queries
 * - BackgroundTrackingApi: For automatic tracking decisions
 * 
 * Architecture rationale:
 * - API objects are static utilities consumed by UI and background tasks
 * - Cannot use @Inject (no lifecycle container)
 * - EntryPoint pattern provides on-demand access without circular dependencies
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackerApiEntryPoint {
	fun trackerServiceController(): TrackerServiceController
	fun lockManager(): LockManager
}
