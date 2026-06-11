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
	 * Returns a page of way ids + bbox columns ordered by primary key using
	 * keyset (seek) pagination for O(n) total cost instead of O(n²) with OFFSET.
	 *
	 * Pass `afterId = 0` (or any value < the smallest id) for the first page.
	 * Thread the last id from each page into the next call.
	 */
	@Query(
		"SELECT id, bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7 " +
			"FROM osm_way WHERE id > :afterId ORDER BY id LIMIT :limit"
	)
	suspend fun pageBboxesAfter(afterId: Long, limit: Int): List<OsmWayBbox>
}
