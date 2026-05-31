package com.adsamcik.tracker.osm.reindex

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayBbox
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds [com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity]
 * rows after [com.adsamcik.tracker.shared.base.database.MIGRATION_31_32] has
 * cleared the table for the new 0.01° [OsmGridIndex] cell size.
 *
 * The reindexer reads only the bbox columns of `osm_way` via
 * [OsmWayDao.pageBboxes], computes cell keys with
 * [OsmGridIndex.cellKeysForBbox] and writes them back into `osm_way_cell` in
 * page-sized batches. The packed polyline blobs and the OSM file the user
 * originally selected are never touched, so the reindex completes in seconds
 * even on multi-million-row imports and works fully offline.
 *
 * Triggering rule: if `osm_import` has at least one row but `osm_way_cell` is
 * empty, the cell index is stale (the v31->v32 migration ran, or the table
 * was manually cleared) and we run a reindex pass. This self-heals across
 * uninstall/restore scenarios without needing an explicit DataStore flag.
 *
 * Concurrency: this is `suspend` and dispatches its IO on
 * [DispatchersProvider.io]; callers should also wrap the call in a
 * background coroutine. It is safe to call multiple times concurrently —
 * the [OsmWayCellDao] insert uses `OnConflictStrategy.IGNORE` and the
 * batched paging is purely idempotent.
 */
@Singleton
class OsmWayCellReindexer @Inject constructor(
	private val osmImportDao: OsmImportDao,
	private val osmWayDao: OsmWayDao,
	private val osmWayCellDao: OsmWayCellDao,
	private val dispatchers: DispatchersProvider,
) {

	/**
	 * Runs the reindex if and only if [needsReindex] reports the index is
	 * stale. Returns the number of cell rows written (0 if the reindex was
	 * skipped or the import had no driveable ways).
	 */
	suspend fun reindexIfNeeded(): Int = withContext(dispatchers.io) {
		if (!needsReindex()) return@withContext 0
		reindex()
	}

	/**
	 * Re-reads `osm_way` in pages and rebuilds the entire `osm_way_cell`
	 * table from scratch. Exposed separately from [reindexIfNeeded] so
	 * tests and dev tools can force a rebuild without touching the import
	 * count heuristic.
	 */
	suspend fun reindex(pageSize: Int = DEFAULT_PAGE_SIZE): Int =
		withContext(dispatchers.io) {
			require(pageSize > 0) { "pageSize must be > 0 (was $pageSize)" }
			var offset = 0
			var totalCellRows = 0
			while (true) {
				val page = osmWayDao.pageBboxes(limit = pageSize, offset = offset)
				if (page.isEmpty()) break
				val cells = page.flatMap(::cellRowsFor)
				if (cells.isNotEmpty()) {
					osmWayCellDao.insertAll(cells)
					totalCellRows += cells.size
				}
				if (page.size < pageSize) break
				offset += page.size
			}
			totalCellRows
		}

	private suspend fun needsReindex(): Boolean {
		val importedRegionCount = osmImportDao.count()
		if (importedRegionCount <= 0) return false
		val wayCount = osmWayDao.count()
		if (wayCount <= 0) return false
		return osmWayCellDao.count() == 0
	}

	private fun cellRowsFor(bbox: OsmWayBbox): List<OsmWayCellEntity> {
		val keys = OsmGridIndex.cellKeysForBbox(
			minLatE7 = bbox.bboxMinLatE7,
			maxLatE7 = bbox.bboxMaxLatE7,
			minLonE7 = bbox.bboxMinLonE7,
			maxLonE7 = bbox.bboxMaxLonE7,
		)
		if (keys.isEmpty()) return emptyList()
		return keys.map { key -> OsmWayCellEntity(cellKey = key, wayId = bbox.id) }
	}

	private companion object {
		// 5000 ways per page → ~120 KB of bbox data per read, comfortably
		// inside the JIT's young generation and small enough that even a
		// many-million-row import streams through with bounded memory.
		const val DEFAULT_PAGE_SIZE = 5_000
	}
}
