package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.OsmWayBbox
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity

/**
 * DAO for [OsmWayEntity].
 *
 * Reads are cell-scoped (see [OsmWayCellDao.findWayIdsInCells]); writes are
 * always done in batches inside the import worker's per-chunk transaction.
 */
@Dao
interface OsmWayDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertAll(ways: Collection<OsmWayEntity>)

	@Query("SELECT * FROM osm_way WHERE id = :id")
	suspend fun findById(id: Long): OsmWayEntity?

	@Query("SELECT * FROM osm_way WHERE id IN (:ids)")
	suspend fun findByIds(ids: Collection<Long>): List<OsmWayEntity>

	@Query("SELECT COUNT(*) FROM osm_way WHERE import_id = :importId")
	suspend fun countForImport(importId: Long): Int

	@Query("SELECT COUNT(*) FROM osm_way")
	suspend fun count(): Int

	/**
	 * Returns a page of way ids + bbox columns ordered by primary key, used by
	 * the cell-index reindexer to rebuild `osm_way_cell` after a cell-size
	 * change. Ordering by `id` keeps pagination stable across batches even if
	 * the table is being written to by an import in another transaction.
	 *
	 * Reads only the bbox columns (no polyline blob), so a 5000-row page is
	 * roughly 120 KB regardless of how big the underlying ways are.
	 */
	@Query(
		"SELECT id, bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7 " +
			"FROM osm_way ORDER BY id LIMIT :limit OFFSET :offset"
	)
	suspend fun pageBboxes(limit: Int, offset: Int): List<OsmWayBbox>
}
