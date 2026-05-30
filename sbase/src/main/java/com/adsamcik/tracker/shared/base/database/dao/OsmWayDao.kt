package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
