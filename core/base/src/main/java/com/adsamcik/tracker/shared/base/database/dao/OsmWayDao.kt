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
 * Reads are cell-scoped (see [OsmWayCellDao.findWayIdsInCells]). New safe
 * intake writes import-scoped instances and their cell rows in one database
 * transaction; the centrally disabled legacy importer remains source
 * compatible but can only fail safely on an overlapping row identity.
 */
@Dao
interface OsmWayDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAll(ways: Collection<OsmWayEntity>): List<Long>

	/**
	 * Future-coordinator contract for a new immutable import generation.
	 *
	 * Call this inside the same Room transaction that inserts the derived cell
	 * rows. Every supplied row must request an auto-generated local id; pair the
	 * returned ids with the input order to build [OsmWayCellEntity] rows. A
	 * duplicate logical `(import_id, osm_way_id)` is deliberately an ABORT
	 * failure, never an update or replacement.
	 */
	suspend fun insertNewImportScopedInstances(ways: Collection<OsmWayEntity>): List<Long> {
		require(ways.all { it.wayInstanceId == 0L }) {
			"New import-scoped OSM rows must request generated way_instance_id values"
		}
		return insertAll(ways)
	}

	/**
	 * Spatial consumers may only read effective, published local instances whose
	 * producer declared the directed-bbox contract and whose cell index is
	 * complete. `id` is a local `way_instance_id` despite the historic method
	 * name; the entity's [OsmWayEntity.id] remains the upstream OSM id.
	 */
	@Query(
		"SELECT osm_way.* FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
		"WHERE osm_way.way_instance_id = :id " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1 " +
			"AND osm_import.cell_index_built = 1 " +
			"AND NOT EXISTS (SELECT 1 FROM osm_way AS higher_way " +
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
	suspend fun findById(id: Long): OsmWayEntity?

	@Query(
		"SELECT osm_way.* FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
		"WHERE osm_way.way_instance_id IN (:ids) " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1 " +
			"AND osm_import.cell_index_built = 1 " +
			"AND NOT EXISTS (SELECT 1 FROM osm_way AS higher_way " +
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
	suspend fun findByIds(ids: Collection<Long>): List<OsmWayEntity>

	@Query("SELECT COUNT(*) FROM osm_way WHERE import_id = :importId")
	suspend fun countForImport(importId: Long): Int

	@Query(
		"SELECT COUNT(*) FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1",
	)
	suspend fun count(): Int

	/**
	 * Returns a page of published, directed-bbox local instance ids + bbox columns ordered by primary key using
	 * keyset (seek) pagination for O(n) total cost instead of O(n²) with OFFSET.
	 *
	 * Pass `afterId = 0` (or any value < the smallest id) for the first page.
	 * Thread the last id from each page into the next call.
	 */
	@Query(
		"SELECT osm_way.way_instance_id AS way_instance_id, bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7 " +
			"FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_way.way_instance_id > :afterId " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1 " +
			"ORDER BY osm_way.way_instance_id LIMIT :limit"
	)
	suspend fun pageBboxesAfter(afterId: Long, limit: Int): List<OsmWayBbox>
}
