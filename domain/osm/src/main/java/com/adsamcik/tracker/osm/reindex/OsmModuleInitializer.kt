package com.adsamcik.tracker.osm.reindex

import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.utils.module.ModuleInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Wires the OSM background tasks that have to run on every app launch.
 *
 * Today that's just the [OsmWayCellReindexer] — kicked off on the IO
 * dispatcher inside the application coroutine scope so a stale
 * `osm_way_cell` table (caused by the v31->v32 migration or any other
 * cell-size change) self-heals without blocking startup or UI threads.
 *
 * Failures inside the reindex job are caught and reported via
 * [ReporterFacade]; we deliberately don't rethrow because the user-visible
 * fallback (`DefaultSpeedLimitSource` returns the fixed baseline while
 * the cell index is empty) is already safe.
 *
 * Priority: 500 — runs after high-priority module init (Activity = 10)
 * but well before any UI-driven speed-limit lookup. The reindex itself
 * happens fully off the startup thread, so this priority just controls
 * when the launch enqueues.
 */
class OsmModuleInitializer @Inject constructor(
	@ApplicationScope private val appScope: CoroutineScope,
	private val dispatchers: DispatchersProvider,
	private val reindexer: OsmWayCellReindexer,
) : ModuleInitializer {

	override val priority: Int = 500

	override fun initialize() {
		appScope.launch(dispatchers.io) {
			try {
				val rowsWritten = reindexer.reindexIfNeeded()
				if (rowsWritten > 0) {
					ReporterFacade.log(
						"OsmModuleInitializer: rebuilt $rowsWritten osm_way_cell rows",
					)
				}
			} catch (cancellation: kotlinx.coroutines.CancellationException) {
				throw cancellation
			} catch (t: Throwable) {
				// Cell index stays empty → DefaultSpeedLimitSource keeps
				// returning the fixed baseline. Safe; just lose the OSM-specific
				// precision for this session.
				ReporterFacade.report(t)
			}
		}
	}
}

