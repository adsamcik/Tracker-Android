package com.adsamcik.tracker.osm.reindex

import com.adsamcik.tracker.logging.api.ReporterFacade
import com.adsamcik.tracker.osm.io.OsmCellCoverage
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
 * The reindexer reads only published directed-bbox columns of `osm_way` via
 * [OsmWayDao.pageBboxesAfter], computes cell keys with
 * [OsmGridIndex.cellCoverageForBbox] and writes them back into `osm_way_cell` in
 * page-sized batches. The packed polyline blobs and the OSM file the user
 * originally selected are never touched, so the reindex completes in seconds
 * even on multi-million-row imports and works fully offline.
 *
 * Triggering rule: if any published import with the current directed-bbox
 * producer version has `cell_index_built = 0`, the cell index is stale or
 * incomplete (the migration ran, or a previous reindex was interrupted by a
 * crash). The reindexer clears `osm_way_cell`, rebuilds from scratch, and
 * marks eligible imports as built only after successful completion.
 * This guarantees crash-safety: if the process dies mid-batch, imports remain
 * flagged 0 and the heuristic re-triggers on next launch.
 *
 * Pagination uses keyset (seek) strategy via [OsmWayDao.pageBboxesAfter] for
 * O(n) total cost, avoiding the O(n²) row scans of OFFSET-based pagination.
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
			val startMs = System.currentTimeMillis()
			var afterId = 0L
			var totalCellRows = 0
			var totalWays = 0
			try {
				while (true) {
					val page = osmWayDao.pageBboxesAfter(
						afterId = afterId,
						limit = pageSize,
					)
					if (page.isEmpty()) break
					totalWays += page.size
					val cells = page.flatMap(::cellRowsFor)
					if (cells.isNotEmpty()) {
						osmWayCellDao.insertAll(cells)
						totalCellRows += cells.size
					}
					afterId = page.last().id
					if (page.size < pageSize) break
				}
				// Mark eligible imports as having a complete cell index only after
				// every batch succeeded. If we crash before this line, they keep
				// cell_index_built = 0 and the heuristic re-triggers.
				osmImportDao.markAllCellIndexBuilt()

				val durationMs = System.currentTimeMillis() - startMs
				ReporterFacade.log(
					"OsmWayCellReindex complete: $totalWays ways → $totalCellRows cells in ${durationMs}ms",
				)
				totalCellRows
			} catch (cancellation: kotlinx.coroutines.CancellationException) {
				throw cancellation
			} catch (t: Throwable) {
				val durationMs = System.currentTimeMillis() - startMs
				ReporterFacade.report(
					RuntimeException(
						"OsmWayCellReindex failed after $totalWays ways, $totalCellRows cells, ${durationMs}ms",
						t,
					),
				)
				throw t
			}
		}

	private suspend fun needsReindex(): Boolean {
		val importedRegionCount = osmImportDao.readyCount()
		if (importedRegionCount <= 0) return false
		val wayCount = osmWayDao.count()
		if (wayCount <= 0) return false
		return osmImportDao.hasUnbuiltCellIndex()
	}

	private fun cellRowsFor(bbox: OsmWayBbox): List<OsmWayCellEntity> = when (
		val coverage = OsmGridIndex.cellCoverageForBbox(
			minLatE7 = bbox.bboxMinLatE7,
			maxLatE7 = bbox.bboxMaxLatE7,
			startLonE7 = bbox.bboxMinLonE7,
			endLonE7 = bbox.bboxMaxLonE7,
		)
	) {
		is OsmCellCoverage.Available -> coverage.cellKeys.map { key ->
			OsmWayCellEntity(cellKey = key, wayId = bbox.id)
		}
		is OsmCellCoverage.TooLarge -> throw IllegalStateException(
			"Cannot reindex way ${bbox.id}: ${coverage.requestedCellCount} cells exceeds " +
				coverage.maximumCellCount,
		)
	}

	private companion object {
		// 5000 ways per page → ~120 KB of bbox data per read, comfortably
		// inside the JIT's young generation and small enough that even a
		// many-million-row import streams through with bounded memory.
		const val DEFAULT_PAGE_SIZE = 5_000
	}
}
