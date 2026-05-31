package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.OsmWayCellEntity

/**
 * DAO for [OsmWayCellEntity], the coarse-grid spatial index between
 * [com.adsamcik.tracker.shared.base.database.data.OsmWayEntity] rows and
 * the grid cells their bounding boxes overlap.
 *
 * `cell_key` is computed by `(latE7 / CELL_E7) << 24 | (lonE7 / CELL_E7) & 0xFFFFFF`,
 * with `CELL_E7` defined in `com.adsamcik.tracker.osm.io.OsmGridIndex`. The
 * 24-bit lon slot leaves headroom for the full [-180°,+180°] range at the
 * current 0.01° cell size and any reasonable future tightening.
 */
@Dao
interface OsmWayCellDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertAll(entries: Collection<OsmWayCellEntity>)

	@Query("SELECT way_id FROM osm_way_cell WHERE cell_key IN (:cellKeys)")
	suspend fun findWayIdsInCells(cellKeys: Collection<Long>): List<Long>

	@Query("SELECT COUNT(*) FROM osm_way_cell")
	suspend fun count(): Int
}
