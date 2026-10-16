package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity

/** Portable radio details never grant local provider, writer, or capture authority. */
@Dao
interface ImportedRadioChildDao {
	/** Resolve the storage origin of exact exported fact IDs without a range-sized history read. */
	@Query("SELECT DISTINCT logical_fact_id FROM ambient_wifi_fact_cursor " +
		"WHERE logical_fact_id IN (:factIds)")
	suspend fun localWifiFactIds(factIds: List<String>): List<String>

	/**
	 * Reads distinct `fact_id` values from `imported_ambient_wifi_fact`. Query conditions include
	 * `fact_id IN (:factIds)`.
	 */
	@Query("SELECT DISTINCT fact_id FROM imported_ambient_wifi_fact " +
		"WHERE fact_id IN (:factIds)")
	suspend fun importedWifiFactIds(factIds: List<String>): List<String>

	/**
	 * Reads distinct `logical_fact_id` values from `ambient_cell_fact_cursor`. Query conditions
	 * include `logical_fact_id IN (:factIds)`.
	 */
	@Query("SELECT DISTINCT logical_fact_id FROM ambient_cell_fact_cursor " +
		"WHERE logical_fact_id IN (:factIds)")
	suspend fun localCellFactIds(factIds: List<String>): List<String>

	/**
	 * Reads distinct `fact_id` values from `imported_ambient_cell_fact`. Query conditions include
	 * `fact_id IN (:factIds)`.
	 */
	@Query("SELECT DISTINCT fact_id FROM imported_ambient_cell_fact " +
		"WHERE fact_id IN (:factIds)")
	suspend fun importedCellFactIds(factIds: List<String>): List<String>

	/**
	 * Reads `imported_wifi_observation` rows. Query conditions include `entry_identity = :entryId`,
	 * `entry_import_revision = :revision`, `run_identity = :runId`, `identity = :observationId`.
	 * Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_wifi_observation WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId AND identity = :observationId")
	suspend fun wifiSessionParent(entryId: String, revision: Long, runId: String,
		observationId: String): ImportedWifiObservationEntity?

	/**
	 * Reads `imported_cell_observation` rows. Query conditions include `entry_identity = :entryId`,
	 * `entry_import_revision = :revision`, `run_identity = :runId`, `identity = :observationId`.
	 * Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_cell_observation WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId AND identity = :observationId")
	suspend fun cellSessionParent(entryId: String, revision: Long, runId: String,
		observationId: String): ImportedCellObservationEntity?

	/**
	 * Reads `imported_ambient_wifi_fact` rows. Query conditions include `fact_id = :factId`,
	 * `semantic_revision = :revision`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_ambient_wifi_fact WHERE fact_id = :factId " +
		"AND semantic_revision = :revision LIMIT 1")
	suspend fun wifiBackgroundParent(factId: String, revision: Long): ImportedAmbientWifiFactEntity?

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `fact_id = :factId`,
	 * `semantic_revision = :revision`. Returns at most 1 row.
	 */
	@Query("SELECT * FROM imported_ambient_cell_fact WHERE fact_id = :factId " +
		"AND semantic_revision = :revision LIMIT 1")
	suspend fun cellBackgroundParent(factId: String, revision: Long): ImportedAmbientCellFactEntity?
	/**
	 * Inserts [ImportedWifiChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertWifiSession(rows: List<ImportedWifiChildEntity>)

	/**
	 * Inserts [ImportedCellChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertCellSession(rows: List<ImportedCellChildEntity>)

	/**
	 * Inserts [ImportedAmbientWifiChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertWifiBackground(rows: List<ImportedAmbientWifiChildEntity>)

	/**
	 * Inserts [ImportedAmbientCellChildEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertCellBackground(rows: List<ImportedAmbientCellChildEntity>)

	/**
	 * Reads `imported_wifi_child` rows. Query conditions include `entry_identity = :entryId`,
	 * `entry_import_revision = :revision`, `run_identity = :runId`, `observation_identity =
	 * :observationId`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_wifi_child WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId " +
		"AND observation_identity = :observationId AND item_index = :itemIndex")
	suspend fun wifiSessionChild(entryId: String, revision: Long, runId: String,
		observationId: String, itemIndex: Int): ImportedWifiChildEntity?

	/**
	 * Reads `imported_cell_child` rows. Query conditions include `entry_identity = :entryId`,
	 * `entry_import_revision = :revision`, `run_identity = :runId`, `observation_identity =
	 * :observationId`. Returns null when no row matches.
	 */
	@Query("SELECT * FROM imported_cell_child WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId " +
		"AND observation_identity = :observationId AND item_index = :itemIndex")
	suspend fun cellSessionChild(entryId: String, revision: Long, runId: String,
		observationId: String, itemIndex: Int): ImportedCellChildEntity?

	/**
	 * Reads `imported_ambient_wifi_child` rows. Query conditions include `archive_id = :archiveId`,
	 * `fact_id = :factId`, `semantic_revision = :revision`, `item_index = :itemIndex`. Returns null
	 * when no row matches.
	 */
	@Query("SELECT * FROM imported_ambient_wifi_child WHERE archive_id = :archiveId " +
		"AND fact_id = :factId AND semantic_revision = :revision AND item_index = :itemIndex")
	suspend fun wifiBackgroundChild(archiveId: String, factId: String, revision: Long,
		itemIndex: Int): ImportedAmbientWifiChildEntity?

	/**
	 * Reads `imported_ambient_cell_child` rows. Query conditions include `archive_id = :archiveId`,
	 * `fact_id = :factId`, `semantic_revision = :revision`, `item_index = :itemIndex`. Returns null
	 * when no row matches.
	 */
	@Query("SELECT * FROM imported_ambient_cell_child WHERE archive_id = :archiveId " +
		"AND fact_id = :factId AND semantic_revision = :revision AND item_index = :itemIndex")
	suspend fun cellBackgroundChild(archiveId: String, factId: String, revision: Long,
		itemIndex: Int): ImportedAmbientCellChildEntity?

	/**
	 * Reads `imported_wifi_child` rows. Query conditions include `entry_identity = :entryIdentity`,
	 * `entry_import_revision = :revision`. Orders by `run_identity, observation_identity,
	 * item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_wifi_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision ORDER BY run_identity, observation_identity, item_index LIMIT :limit")
	suspend fun wifiSessionChildren(entryIdentity: String, revision: Long, limit: Int): List<ImportedWifiChildEntity>

	/**
	 * Reads `imported_cell_child` rows. Query conditions include `entry_identity = :entryIdentity`,
	 * `entry_import_revision = :revision`. Orders by `run_identity, observation_identity,
	 * item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_cell_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision ORDER BY run_identity, observation_identity, item_index LIMIT :limit")
	suspend fun cellSessionChildren(entryIdentity: String, revision: Long, limit: Int): List<ImportedCellChildEntity>

	/** Bounded details only for the qualified, surviving observation owners in this snapshot. */
	@Query("SELECT * FROM imported_wifi_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision AND run_identity = :runIdentity " +
		"AND observation_identity IN (:observationIds) " +
		"ORDER BY run_identity, observation_identity, item_index LIMIT :limit")
	suspend fun wifiSessionChildrenForObservations(entryIdentity: String, revision: Long, runIdentity: String,
		observationIds: List<String>, limit: Int): List<ImportedWifiChildEntity>

	/**
	 * Reads `imported_cell_child` rows. Query conditions include `entry_identity = :entryIdentity`,
	 * `entry_import_revision = :revision`, `run_identity = :runIdentity`, `observation_identity IN
	 * (:observationIds)`. Orders by `run_identity, observation_identity, item_index`. Returns at most
	 * `limit` rows.
	 */
	@Query("SELECT * FROM imported_cell_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision AND run_identity = :runIdentity " +
		"AND observation_identity IN (:observationIds) " +
		"ORDER BY run_identity, observation_identity, item_index LIMIT :limit")
	suspend fun cellSessionChildrenForObservations(entryIdentity: String, revision: Long, runIdentity: String,
		observationIds: List<String>, limit: Int): List<ImportedCellChildEntity>

	/**
	 * Reads `imported_ambient_wifi_child` rows. Query conditions include `archive_id = :archiveId`.
	 * Orders by `fact_id, semantic_revision, item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_wifi_child WHERE archive_id = :archiveId " +
		"ORDER BY fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun wifiBackgroundChildren(archiveId: String, limit: Int): List<ImportedAmbientWifiChildEntity>

	/**
	 * Reads `imported_ambient_wifi_child` rows. Query conditions include `fact_id IN (:factIds)`.
	 * Orders by `fact_id, semantic_revision, item_index, archive_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_wifi_child WHERE fact_id IN (:factIds) " +
		"ORDER BY fact_id, semantic_revision, item_index, archive_id LIMIT :limit")
	suspend fun wifiBackgroundChildrenForFacts(factIds: List<String>, limit: Int): List<ImportedAmbientWifiChildEntity>

	/**
	 * Reads `imported_ambient_wifi_child` rows. Query conditions include `fact_id = :factId`,
	 * `semantic_revision = :revision`. Orders by `archive_id, item_index`. Returns at most `limit`
	 * rows.
	 */
	@Query("SELECT * FROM imported_ambient_wifi_child WHERE fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY archive_id, item_index LIMIT :limit")
	suspend fun wifiBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<ImportedAmbientWifiChildEntity>

	/**
	 * Reads `imported_ambient_wifi_child` rows. Query conditions include `child.fact_id IN
	 * (:factIds)`. Orders by `child.fact_id, child.item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT child.* FROM imported_ambient_wifi_child AS child " +
		"INNER JOIN imported_ambient_wifi_fact AS fact ON fact.archive_id = child.archive_id " +
		"AND fact.fact_id = child.fact_id AND fact.semantic_revision = child.semantic_revision " +
		"WHERE child.fact_id IN (:factIds) AND fact.semantic_revision = " +
		"(SELECT MAX(latest.semantic_revision) FROM imported_ambient_wifi_fact AS latest " +
		"WHERE latest.fact_id = child.fact_id) " +
		"ORDER BY child.fact_id, child.item_index LIMIT :limit")
	suspend fun wifiCurrentBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<ImportedAmbientWifiChildEntity>

	/**
	 * Reads `imported_ambient_cell_child` rows. Query conditions include `archive_id = :archiveId`.
	 * Orders by `fact_id, semantic_revision, item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_cell_child WHERE archive_id = :archiveId " +
		"ORDER BY fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun cellBackgroundChildren(archiveId: String, limit: Int): List<ImportedAmbientCellChildEntity>

	/**
	 * Reads `imported_ambient_cell_child` rows. Query conditions include `fact_id IN (:factIds)`.
	 * Orders by `fact_id, semantic_revision, item_index, archive_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_cell_child WHERE fact_id IN (:factIds) " +
		"ORDER BY fact_id, semantic_revision, item_index, archive_id LIMIT :limit")
	suspend fun cellBackgroundChildrenForFacts(factIds: List<String>, limit: Int): List<ImportedAmbientCellChildEntity>

	/**
	 * Reads `imported_ambient_cell_child` rows. Query conditions include `fact_id = :factId`,
	 * `semantic_revision = :revision`. Orders by `archive_id, item_index`. Returns at most `limit`
	 * rows.
	 */
	@Query("SELECT * FROM imported_ambient_cell_child WHERE fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY archive_id, item_index LIMIT :limit")
	suspend fun cellBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<ImportedAmbientCellChildEntity>

	/**
	 * Reads `imported_ambient_cell_child` rows. Query conditions include `child.fact_id IN
	 * (:factIds)`. Orders by `child.fact_id, child.item_index`. Returns at most `limit` rows.
	 */
	@Query("SELECT child.* FROM imported_ambient_cell_child AS child " +
		"INNER JOIN imported_ambient_cell_fact AS fact ON fact.archive_id = child.archive_id " +
		"AND fact.fact_id = child.fact_id AND fact.semantic_revision = child.semantic_revision " +
		"WHERE child.fact_id IN (:factIds) AND fact.semantic_revision = " +
		"(SELECT MAX(latest.semantic_revision) FROM imported_ambient_cell_fact AS latest " +
		"WHERE latest.fact_id = child.fact_id) " +
		"ORDER BY child.fact_id, child.item_index LIMIT :limit")
	suspend fun cellCurrentBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<ImportedAmbientCellChildEntity>

	/**
	 * Deletes `imported_wifi_child` rows matching the supplied scope. Query conditions include
	 * `entry_identity = :entryIdentity`, `entry_import_revision = :revision`. Returns the affected
	 * row count.
	 */
	@Query("DELETE FROM imported_wifi_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision")
	suspend fun deleteWifiSession(entryIdentity: String, revision: Long): Int

	/**
	 * Deletes `imported_cell_child` rows matching the supplied scope. Query conditions include
	 * `entry_identity = :entryIdentity`, `entry_import_revision = :revision`. Returns the affected
	 * row count.
	 */
	@Query("DELETE FROM imported_cell_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision")
	suspend fun deleteCellSession(entryIdentity: String, revision: Long): Int

	/**
	 * Deletes `imported_ambient_wifi_child` rows matching the supplied scope. Query conditions
	 * include `archive_id = :archiveId`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_wifi_child WHERE archive_id = :archiveId")
	suspend fun deleteWifiBackground(archiveId: String): Int

	/**
	 * Deletes `imported_ambient_cell_child` rows matching the supplied scope. Query conditions
	 * include `archive_id = :archiveId`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_child WHERE archive_id = :archiveId")
	suspend fun deleteCellBackground(archiveId: String): Int

	/**
	 * Deletes `imported_wifi_child` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_wifi_child")
	suspend fun deleteAllWifiSession(): Int

	/**
	 * Deletes `imported_cell_child` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_cell_child")
	suspend fun deleteAllCellSession(): Int

	/**
	 * Deletes `imported_ambient_wifi_child` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_wifi_child")
	suspend fun deleteAllWifiBackground(): Int

	/**
	 * Deletes `imported_ambient_cell_child` rows.
	 */
	@Query("DELETE FROM imported_ambient_cell_child")
	suspend fun deleteAllCellBackground(): Int
}
