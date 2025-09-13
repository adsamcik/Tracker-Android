package com.adsamcik.tracker.app

import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import kotlinx.coroutines.CoroutineScope

/**
 * Minimal application composition root.
 * Extend with repositories/services as they are migrated to constructor injection.
 */
class AppGraph(
    val dispatchers: DispatchersProvider,
    val appScope: CoroutineScope,
) {
    // Future: val database: AppDatabase, val trackerRepository: TrackerRepository, etc.
}
