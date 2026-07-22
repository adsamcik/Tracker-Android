package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.OsmImportEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [OsmImportEntity] header rows.
 *
 * "Has-import" presence is the runtime signal that switches
 * `DefaultSpeedLimitSource` over from the fixed baseline to the OSM-backed
 * implementation. Keep the count query cheap.
 */
@Dao
interface OsmImportDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insert(osmImport: OsmImportEntity): Long

	@Query("SELECT * FROM osm_import ORDER BY imported_at DESC")
	fun observeAll(): Flow<List<OsmImportEntity>>

	@Query("SELECT * FROM osm_import WHERE status = 'READY' ORDER BY imported_at DESC")
	fun observeReady(): Flow<List<OsmImportEntity>>

	@Query("SELECT * FROM osm_import ORDER BY imported_at DESC LIMIT 1")
	fun observeLatest(): Flow<OsmImportEntity?>

	@Query("SELECT * FROM osm_import ORDER BY imported_at DESC LIMIT 1")
	suspend fun getLatest(): OsmImportEntity?

	@Query("SELECT COUNT(*) FROM osm_import")
	suspend fun count(): Int

	/** Only published imports with directed child-way bboxes are spatially usable. */
	@Query(
		"SELECT COUNT(*) FROM osm_import " +
			"WHERE status = 'READY' AND way_bbox_encoding_version = 1",
	)
	suspend fun readyCount(): Int

	/**
	 * Returns true when at least one import exists whose cell index has not
	 * been fully built (or was interrupted mid-build). The reindexer uses this
	 * instead of `osm_way_cell.count == 0` so a crash mid-reindex is always
	 * detected.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM osm_import " +
			"WHERE cell_index_built = 0 " +
			"AND status = 'READY' " +
			"AND way_bbox_encoding_version = 1)",
	)
	suspend fun hasUnbuiltCellIndex(): Boolean

	/**
	 * Marks all imports as having a complete cell index. Called by the
	 * reindexer after a successful full rebuild.
	 */
	@Query(
		"UPDATE osm_import SET cell_index_built = 1 " +
			"WHERE status = 'READY' AND way_bbox_encoding_version = 1",
	)
	suspend fun markAllCellIndexBuilt()

	@Query("SELECT COUNT(*) FROM osm_import")
	fun observeCount(): Flow<Int>

	@Query(
		"SELECT COUNT(*) FROM osm_import " +
			"WHERE status = 'READY' AND way_bbox_encoding_version = 1",
	)
	fun observeReadyCount(): Flow<Int>

	/**
	 * Atomically publishes a successfully completed import. The header row is
	 * inserted as BUILDING so child [OsmWayEntity] rows can FK to it; this one
	 * statement writes the final metadata and changes visibility to READY.
	 */
	@Query(
		"UPDATE osm_import SET way_count = :wayCount, node_count = :nodeCount, " +
			"min_lat_e7 = :diagnosticMinLatitudeE7, max_lat_e7 = :diagnosticMaxLatitudeE7, " +
			"min_lon_e7 = :diagnosticMinLongitudeE7, max_lon_e7 = :diagnosticMaxLongitudeE7, status = 'READY' " +
			"WHERE id = :importId AND status = 'BUILDING'",
	)
	suspend fun markReady(
		importId: Long,
		wayCount: Long,
		nodeCount: Long,
		diagnosticMinLatitudeE7: Int,
		diagnosticMaxLatitudeE7: Int,
		diagnosticMinLongitudeE7: Int,
		diagnosticMaxLongitudeE7: Int,
	): Int

	/** Cascades to osm_way and osm_way_cell via FK. */
	@Query("DELETE FROM osm_import WHERE id = :importId")
	suspend fun delete(importId: Long)

	/** Removes imports left unpublished by a prior process death. */
	@Query("DELETE FROM osm_import WHERE status = 'BUILDING'")
	suspend fun deleteBuildingImports(): Int

	/**
	 * OSM tables are unreleased. Drop development imports whose child-way bbox
	 * values use an obsolete encoding instead of reinterpreting ordered extrema
	 * as directed circular arcs.
	 */
	@Query("DELETE FROM osm_import WHERE way_bbox_encoding_version <> :supportedVersion")
	suspend fun deleteImportsWithUnsupportedWayBboxEncoding(supportedVersion: Int): Int

	@Query("DELETE FROM osm_import")
	fun deleteAll()

	/**
	 * Wipe everything OSM in a single transaction. Used by "remove imported region"
	 * and by `AppDatabase.deleteAllCollectedData`.
	 *
	 * We do the per-table DELETEs explicitly so that the call still works even
	 * if SQLite foreign-keys are off (Robolectric test database default).
	 *
	 * Non-suspend so it can be called from
	 * [androidx.room.RoomDatabase.runInTransaction], which expects a regular
	 * lambda.
	 */
	@Transaction
	fun deleteAllTables() {
		deleteAllWayCells()
		deleteAllWays()
		deleteAll()
	}

	@Query("DELETE FROM osm_way")
	fun deleteAllWays()

	@Query("DELETE FROM osm_way_cell")
	fun deleteAllWayCells()
}
