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

	/**
	 * Old numeric-extrema rows are not spatially readable as directed bboxes.
	 * Join through the import header so stale cell-index rows cannot leak them
	 * into lookup, geocoding, or matching before startup cleanup deletes them.
	 */
	@Query(
		"SELECT osm_way_cell.way_id FROM osm_way_cell " +
			"INNER JOIN osm_way ON osm_way.id = osm_way_cell.way_id " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_way_cell.cell_key IN (:cellKeys) " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1",
	)
	suspend fun findWayIdsInCells(cellKeys: Collection<Long>): List<Long>

	@Query("SELECT COUNT(*) FROM osm_way_cell")
	suspend fun count(): Int
}
