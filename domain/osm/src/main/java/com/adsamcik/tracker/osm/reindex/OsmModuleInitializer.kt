package com.adsamcik.tracker.osm.reindex

import com.adsamcik.tracker.diagnostics.TrackerDiagnosticCode
import com.adsamcik.tracker.diagnostics.TrackerDiagnostics
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import com.adsamcik.tracker.shared.base.di.ApplicationScope
import com.adsamcik.tracker.shared.base.startup.ModuleInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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
 * Failures inside the reindex job are recorded as a fixed diagnostic event; we deliberately don't
 * rethrow because the user-visible
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
	private val osmImportDao: OsmImportDao,
	private val reindexer: OsmWayCellReindexer,
) : ModuleInitializer {

	override val priority: Int = 500

	override fun initialize() {
		val startupStartedAt = System.currentTimeMillis()
		appScope.launch(dispatchers.io) {
			try {
				val abandonedImports = osmImportDao.observeAll().first()
					.filter {
						it.status == "BUILDING" && it.importedAt < startupStartedAt
					}
				abandonedImports.forEach { osmImportDao.delete(it.id) }
				// OSM tables have not shipped. Old development rows stored ordinary
				// longitude extrema, which are ambiguous and cannot safely be
				// reinterpreted as the directed child-way contract used by V10.
				osmImportDao.deleteImportsWithUnsupportedWayBboxEncoding(
					OsmImportEntity.WAY_BBOX_ENCODING_DIRECTED_V1,
				)
				reindexer.reindexIfNeeded()
			} catch (cancellation: kotlinx.coroutines.CancellationException) {
				throw cancellation
			} catch (t: Throwable) {
				// Cell index stays empty → DefaultSpeedLimitSource keeps
				// returning the fixed baseline. Safe; just lose the OSM-specific
				// precision for this session.
				TrackerDiagnostics.record(TrackerDiagnosticCode.OSM_IMPORT_FAILED)
			}
		}
	}
}
