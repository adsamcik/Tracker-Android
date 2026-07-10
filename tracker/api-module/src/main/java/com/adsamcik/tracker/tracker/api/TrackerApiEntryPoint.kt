package com.adsamcik.tracker.tracker.api

import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt EntryPoint for API classes to access tracker state/controller services.
 * 
 * Used by:
 * - TrackerServiceApi: For session state queries via [TrackerStateReader]
 * - BackgroundTrackingApi: For automatic tracking decisions via [TrackerStateReader]
 * 
 * Architecture rationale:
 * - API objects are static utilities consumed by UI and background tasks
 * - Cannot use @Inject (no lifecycle container)
 * - EntryPoint pattern provides on-demand access without circular dependencies
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TrackerApiEntryPoint {
	fun trackerStateReader(): TrackerStateReader
	fun lockManager(): LockManager
}
