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

	@Query("SELECT * FROM osm_import ORDER BY imported_at DESC LIMIT 1")
	fun observeLatest(): Flow<OsmImportEntity?>

	@Query("SELECT * FROM osm_import ORDER BY imported_at DESC LIMIT 1")
	suspend fun getLatest(): OsmImportEntity?

	@Query("SELECT COUNT(*) FROM osm_import")
	suspend fun count(): Int

	@Query("SELECT COUNT(*) FROM osm_import")
	fun observeCount(): Flow<Int>

	/** Cascades to osm_way and osm_way_cell via FK. */
	@Query("DELETE FROM osm_import WHERE id = :importId")
	suspend fun delete(importId: Long)

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
