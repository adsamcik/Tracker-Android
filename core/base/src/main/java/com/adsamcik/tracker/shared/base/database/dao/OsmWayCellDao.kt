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
	/**
	 * A duplicate cell membership within an immutable import generation is an
	 * importer invariant failure. ABORT protects a READY graph from the old
	 * global-id replacement behaviour; a future coordinator writes each
	 * way-plus-cell batch in one transaction.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAll(entries: Collection<OsmWayCellEntity>)

	/** Clears every cell row while all READY imports are query-withdrawn. */
	@Query("DELETE FROM osm_way_cell")
	suspend fun deleteAll()

	/**
	 * Old numeric-extrema rows and incomplete rebuilds are not spatially
	 * readable. Join through the owning import so a BUILDING generation, a
	 * withdrawn index, or stale cells cannot leak into lookup, geocoding, or
	 * matching. The anti-join ranks all READY copies of an OSM id before this
	 * query's cell filter, so an old geometry cannot reappear in a cell that a
	 * newer winning geometry has vacated.
	 *
	 * Despite its retained source-compatible name, the returned values are local
	 * `way_instance_id`s, not upstream OSM ids.
	 */
	@Query(
		"SELECT osm_way_cell.way_id FROM osm_way_cell " +
			"INNER JOIN osm_way ON osm_way.way_instance_id = osm_way_cell.way_id " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_way_cell.cell_key IN (:cellKeys) " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1 " +
			"AND osm_import.cell_index_built = 1 " +
			"AND NOT EXISTS (" +
			"SELECT 1 FROM osm_way AS higher_way " +
			"INNER JOIN osm_import AS higher_import ON higher_import.id = higher_way.import_id " +
			"WHERE higher_way.osm_way_id = osm_way.osm_way_id " +
			"AND higher_import.status = 'READY' " +
			"AND higher_import.way_bbox_encoding_version = 1 " +
			"AND higher_import.cell_index_built = 1 " +
			"AND ((higher_way.osm_version IS NOT NULL AND " +
			"(osm_way.osm_version IS NULL OR higher_way.osm_version > osm_way.osm_version)) " +
			"OR (((higher_way.osm_version IS NULL AND osm_way.osm_version IS NULL) " +
			"OR higher_way.osm_version = osm_way.osm_version) " +
			"AND (higher_import.published_revision > osm_import.published_revision " +
			"OR (higher_import.published_revision = osm_import.published_revision " +
			"AND higher_import.id > osm_import.id)))) )",
	)
	suspend fun findWayIdsInCells(cellKeys: Collection<Long>): List<Long>

	@Query("SELECT COUNT(*) FROM osm_way_cell")
	suspend fun count(): Int
}
