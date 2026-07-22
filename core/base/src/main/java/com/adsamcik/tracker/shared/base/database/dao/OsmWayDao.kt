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

	/**
	 * Spatial consumers may only read published rows whose producer declared the
	 * directed-bbox contract. This is defense in depth for the startup cleanup:
	 * an old development row cannot be interpreted as a circular interval during
	 * the brief window before it is deleted.
	 */
	@Query(
		"SELECT osm_way.* FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_way.id = :id " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1",
	)
	suspend fun findById(id: Long): OsmWayEntity?

	@Query(
		"SELECT osm_way.* FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_way.id IN (:ids) " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1",
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
	 * Returns a page of published, directed-bbox way ids + bbox columns ordered by primary key using
	 * keyset (seek) pagination for O(n) total cost instead of O(n²) with OFFSET.
	 *
	 * Pass `afterId = 0` (or any value < the smallest id) for the first page.
	 * Thread the last id from each page into the next call.
	 */
	@Query(
		"SELECT osm_way.id AS id, bbox_min_lat_e7, bbox_max_lat_e7, bbox_min_lon_e7, bbox_max_lon_e7 " +
			"FROM osm_way " +
			"INNER JOIN osm_import ON osm_import.id = osm_way.import_id " +
			"WHERE osm_way.id > :afterId " +
			"AND osm_import.status = 'READY' " +
			"AND osm_import.way_bbox_encoding_version = 1 " +
			"ORDER BY osm_way.id LIMIT :limit"
	)
	suspend fun pageBboxesAfter(afterId: Long, limit: Int): List<OsmWayBbox>
}
