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

	@Query("SELECT DISTINCT fact_id FROM imported_ambient_wifi_fact " +
		"WHERE fact_id IN (:factIds)")
	suspend fun importedWifiFactIds(factIds: List<String>): List<String>

	@Query("SELECT DISTINCT logical_fact_id FROM ambient_cell_fact_cursor " +
		"WHERE logical_fact_id IN (:factIds)")
	suspend fun localCellFactIds(factIds: List<String>): List<String>

	@Query("SELECT DISTINCT fact_id FROM imported_ambient_cell_fact " +
		"WHERE fact_id IN (:factIds)")
	suspend fun importedCellFactIds(factIds: List<String>): List<String>

	@Query("SELECT * FROM imported_wifi_observation WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId AND identity = :observationId")
	suspend fun wifiSessionParent(entryId: String, revision: Long, runId: String,
		observationId: String): ImportedWifiObservationEntity?

	@Query("SELECT * FROM imported_cell_observation WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId AND identity = :observationId")
	suspend fun cellSessionParent(entryId: String, revision: Long, runId: String,
		observationId: String): ImportedCellObservationEntity?

	@Query("SELECT * FROM imported_ambient_wifi_fact WHERE fact_id = :factId " +
		"AND semantic_revision = :revision LIMIT 1")
	suspend fun wifiBackgroundParent(factId: String, revision: Long): ImportedAmbientWifiFactEntity?

	@Query("SELECT * FROM imported_ambient_cell_fact WHERE fact_id = :factId " +
		"AND semantic_revision = :revision LIMIT 1")
	suspend fun cellBackgroundParent(factId: String, revision: Long): ImportedAmbientCellFactEntity?
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertWifiSession(rows: List<ImportedWifiChildEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertCellSession(rows: List<ImportedCellChildEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertWifiBackground(rows: List<ImportedAmbientWifiChildEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertCellBackground(rows: List<ImportedAmbientCellChildEntity>)

	@Query("SELECT * FROM imported_wifi_child WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId " +
		"AND observation_identity = :observationId AND item_index = :itemIndex")
	suspend fun wifiSessionChild(entryId: String, revision: Long, runId: String,
		observationId: String, itemIndex: Int): ImportedWifiChildEntity?

	@Query("SELECT * FROM imported_cell_child WHERE entry_identity = :entryId " +
		"AND entry_import_revision = :revision AND run_identity = :runId " +
		"AND observation_identity = :observationId AND item_index = :itemIndex")
	suspend fun cellSessionChild(entryId: String, revision: Long, runId: String,
		observationId: String, itemIndex: Int): ImportedCellChildEntity?

	@Query("SELECT * FROM imported_ambient_wifi_child WHERE archive_id = :archiveId " +
		"AND fact_id = :factId AND semantic_revision = :revision AND item_index = :itemIndex")
	suspend fun wifiBackgroundChild(archiveId: String, factId: String, revision: Long,
		itemIndex: Int): ImportedAmbientWifiChildEntity?

	@Query("SELECT * FROM imported_ambient_cell_child WHERE archive_id = :archiveId " +
		"AND fact_id = :factId AND semantic_revision = :revision AND item_index = :itemIndex")
	suspend fun cellBackgroundChild(archiveId: String, factId: String, revision: Long,
		itemIndex: Int): ImportedAmbientCellChildEntity?

	@Query("SELECT * FROM imported_wifi_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision ORDER BY run_identity, observation_identity, item_index LIMIT :limit")
	suspend fun wifiSessionChildren(entryIdentity: String, revision: Long, limit: Int): List<ImportedWifiChildEntity>

	@Query("SELECT * FROM imported_cell_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision ORDER BY run_identity, observation_identity, item_index LIMIT :limit")
	suspend fun cellSessionChildren(entryIdentity: String, revision: Long, limit: Int): List<ImportedCellChildEntity>

	@Query("SELECT * FROM imported_ambient_wifi_child WHERE archive_id = :archiveId " +
		"ORDER BY fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun wifiBackgroundChildren(archiveId: String, limit: Int): List<ImportedAmbientWifiChildEntity>

	@Query("SELECT * FROM imported_ambient_wifi_child WHERE fact_id IN (:factIds) " +
		"ORDER BY fact_id, semantic_revision, item_index, archive_id LIMIT :limit")
	suspend fun wifiBackgroundChildrenForFacts(factIds: List<String>, limit: Int): List<ImportedAmbientWifiChildEntity>

	@Query("SELECT * FROM imported_ambient_wifi_child WHERE fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY archive_id, item_index LIMIT :limit")
	suspend fun wifiBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<ImportedAmbientWifiChildEntity>

	@Query("SELECT child.* FROM imported_ambient_wifi_child AS child " +
		"INNER JOIN imported_ambient_wifi_fact AS fact ON fact.archive_id = child.archive_id " +
		"AND fact.fact_id = child.fact_id AND fact.semantic_revision = child.semantic_revision " +
		"WHERE child.fact_id IN (:factIds) AND fact.semantic_revision = " +
		"(SELECT MAX(latest.semantic_revision) FROM imported_ambient_wifi_fact AS latest " +
		"WHERE latest.fact_id = child.fact_id) " +
		"ORDER BY child.fact_id, child.item_index LIMIT :limit")
	suspend fun wifiCurrentBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<ImportedAmbientWifiChildEntity>

	@Query("SELECT * FROM imported_ambient_cell_child WHERE archive_id = :archiveId " +
		"ORDER BY fact_id, semantic_revision, item_index LIMIT :limit")
	suspend fun cellBackgroundChildren(archiveId: String, limit: Int): List<ImportedAmbientCellChildEntity>

	@Query("SELECT * FROM imported_ambient_cell_child WHERE fact_id IN (:factIds) " +
		"ORDER BY fact_id, semantic_revision, item_index, archive_id LIMIT :limit")
	suspend fun cellBackgroundChildrenForFacts(factIds: List<String>, limit: Int): List<ImportedAmbientCellChildEntity>

	@Query("SELECT * FROM imported_ambient_cell_child WHERE fact_id = :factId " +
		"AND semantic_revision = :revision ORDER BY archive_id, item_index LIMIT :limit")
	suspend fun cellBackgroundChildrenForRevision(factId: String, revision: Long,
		limit: Int): List<ImportedAmbientCellChildEntity>

	@Query("SELECT child.* FROM imported_ambient_cell_child AS child " +
		"INNER JOIN imported_ambient_cell_fact AS fact ON fact.archive_id = child.archive_id " +
		"AND fact.fact_id = child.fact_id AND fact.semantic_revision = child.semantic_revision " +
		"WHERE child.fact_id IN (:factIds) AND fact.semantic_revision = " +
		"(SELECT MAX(latest.semantic_revision) FROM imported_ambient_cell_fact AS latest " +
		"WHERE latest.fact_id = child.fact_id) " +
		"ORDER BY child.fact_id, child.item_index LIMIT :limit")
	suspend fun cellCurrentBackgroundChildrenForFactIds(factIds: List<String>,
		limit: Int): List<ImportedAmbientCellChildEntity>

	@Query("DELETE FROM imported_wifi_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision")
	suspend fun deleteWifiSession(entryIdentity: String, revision: Long): Int

	@Query("DELETE FROM imported_cell_child WHERE entry_identity = :entryIdentity " +
		"AND entry_import_revision = :revision")
	suspend fun deleteCellSession(entryIdentity: String, revision: Long): Int

	@Query("DELETE FROM imported_ambient_wifi_child WHERE archive_id = :archiveId")
	suspend fun deleteWifiBackground(archiveId: String): Int

	@Query("DELETE FROM imported_ambient_cell_child WHERE archive_id = :archiveId")
	suspend fun deleteCellBackground(archiveId: String): Int

	@Query("DELETE FROM imported_wifi_child")
	suspend fun deleteAllWifiSession(): Int

	@Query("DELETE FROM imported_cell_child")
	suspend fun deleteAllCellSession(): Int

	@Query("DELETE FROM imported_ambient_wifi_child")
	suspend fun deleteAllWifiBackground(): Int

	@Query("DELETE FROM imported_ambient_cell_child")
	suspend fun deleteAllCellBackground(): Int
}
