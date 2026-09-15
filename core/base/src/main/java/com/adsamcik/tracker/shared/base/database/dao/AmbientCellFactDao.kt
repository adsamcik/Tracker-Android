package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellTombstoneEntity

data class AmbientCellEffectiveLocalRow(
	@Embedded val fact: AmbientCellFactRevisionEntity,
	@ColumnInfo(name = "owner_observation_count") val ownerObservationCount: Int?,
	@ColumnInfo(name = "owner_registered_observation_count") val ownerRegisteredObservationCount: Int?,
	@ColumnInfo(name = "owner_gsm_count") val ownerGsmCount: Int?,
	@ColumnInfo(name = "owner_cdma_count") val ownerCdmaCount: Int?,
	@ColumnInfo(name = "owner_wcdma_count") val ownerWcdmaCount: Int?,
	@ColumnInfo(name = "owner_tdscdma_count") val ownerTdscdmaCount: Int?,
	@ColumnInfo(name = "owner_lte_count") val ownerLteCount: Int?,
	@ColumnInfo(name = "owner_nr_count") val ownerNrCount: Int?,
	@ColumnInfo(name = "owner_quality_unknown_count") val ownerQualityUnknownCount: Int?,
	@ColumnInfo(name = "owner_quality_none_or_unknown_count")
	val ownerQualityNoneOrUnknownCount: Int?,
	@ColumnInfo(name = "owner_quality_poor_count") val ownerQualityPoorCount: Int?,
	@ColumnInfo(name = "owner_quality_moderate_count") val ownerQualityModerateCount: Int?,
	@ColumnInfo(name = "owner_quality_good_count") val ownerQualityGoodCount: Int?,
	@ColumnInfo(name = "owner_quality_great_count") val ownerQualityGreatCount: Int?,
)

/** Bounded source-local storage boundary for Ambient Cell product evidence. */
@Dao
interface AmbientCellFactDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAuthority(value: AmbientCellAuthorityEntity)

	@Query("SELECT COALESCE(MAX(authority_revision), 0) FROM ambient_cell_authority")
	suspend fun maximumAuthorityRevision(): Long

	@Query("SELECT * FROM ambient_cell_authority ORDER BY authority_revision DESC LIMIT 1")
	suspend fun latestAuthority(): AmbientCellAuthorityEntity?

	@Query(
		"SELECT * FROM ambient_cell_authority WHERE effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authority_revision DESC LIMIT 1",
	)
	suspend fun authorityAt(
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): AmbientCellAuthorityEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(value: AmbientCellFactRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(value: AmbientCellFactCursorEntity): Long

	@Query(
		"SELECT * FROM ambient_cell_fact_cursor WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): AmbientCellFactCursorEntity?

	@Query(
		"SELECT * FROM ambient_cell_fact_revision WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId " +
			"AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun revision(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): AmbientCellFactRevisionEntity?

	@Query(
		"UPDATE ambient_cell_fact_cursor SET latest_semantic_revision = :newSemanticRevision, " +
			"latest_mutation_id = :newMutationId, latest_effect_checksum = :newEffectChecksum, " +
			"latest_source_admission_ordinal = :newAdmissionOrdinal, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs WHERE writer_id = :writerId AND writer_version = :writerVersion " +
			"AND logical_fact_id = :logicalFactId AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND latest_source_admission_ordinal = :expectedAdmissionOrdinal " +
			"AND cursor_revision = :expectedCursorRevision AND collected_data_epoch = :collectedDataEpoch " +
			"AND scope_deletion_generation = :scopeDeletionGeneration",
	)
	suspend fun advanceCursorExact(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
		expectedSemanticRevision: Long,
		expectedMutationId: String,
		expectedEffectChecksum: String,
		expectedAdmissionOrdinal: Long,
		expectedCursorRevision: Long,
		newSemanticRevision: Long,
		newMutationId: String,
		newEffectChecksum: String,
		newAdmissionOrdinal: Long,
		newCursorRevision: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"SELECT fact.* FROM ambient_cell_fact_revision AS fact " +
			"INNER JOIN ambient_cell_fact_cursor AS cursor ON cursor.writer_id = fact.writer_id " +
			"AND cursor.writer_version = fact.writer_version AND cursor.logical_fact_id = fact.logical_fact_id " +
			"AND cursor.latest_semantic_revision = fact.semantic_revision " +
			"WHERE fact.fact_kind = '${AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE}' " +
			"AND fact.ambient_consent_epoch = :ambientConsentEpoch " +
			"AND fact.collected_data_epoch = :collectedDataEpoch " +
			"AND fact.scope_deletion_generation = :scopeDeletionGeneration " +
			"AND fact.source_admission_ordinal < :beforeAdmissionOrdinal " +
			"ORDER BY fact.source_admission_ordinal DESC LIMIT 1",
	)
	suspend fun latestAggregateBefore(
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		beforeAdmissionOrdinal: Long,
	): AmbientCellFactRevisionEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertGap(value: AmbientCellGapEntity): Long

	@Query(
		"SELECT fact.*, owner.observation_count AS owner_observation_count, " +
			"owner.registered_observation_count AS owner_registered_observation_count, " +
			"owner.gsm_count AS owner_gsm_count, owner.cdma_count AS owner_cdma_count, " +
			"owner.wcdma_count AS owner_wcdma_count, owner.tdscdma_count AS owner_tdscdma_count, " +
			"owner.lte_count AS owner_lte_count, owner.nr_count AS owner_nr_count, " +
			"owner.quality_unknown_count AS owner_quality_unknown_count, " +
			"owner.quality_none_or_unknown_count AS owner_quality_none_or_unknown_count, " +
			"owner.quality_poor_count AS owner_quality_poor_count, " +
			"owner.quality_moderate_count AS owner_quality_moderate_count, " +
			"owner.quality_good_count AS owner_quality_good_count, " +
			"owner.quality_great_count AS owner_quality_great_count " +
			"FROM ambient_cell_fact_revision AS fact " +
			"INNER JOIN ambient_cell_fact_cursor AS cursor ON cursor.writer_id = fact.writer_id " +
			"AND cursor.writer_version = fact.writer_version AND cursor.logical_fact_id = fact.logical_fact_id " +
			"AND cursor.latest_semantic_revision = fact.semantic_revision " +
			"LEFT JOIN ambient_cell_fact_revision AS owner ON owner.writer_id = fact.writer_id " +
			"AND owner.writer_version = fact.writer_version " +
			"AND owner.logical_fact_id = fact.aggregate_owner_logical_fact_id " +
			"AND owner.semantic_revision = fact.aggregate_owner_semantic_revision " +
			"WHERE fact.observed_wall_time_ms >= :fromInclusiveMs " +
			"AND fact.observed_wall_time_ms < :toExclusiveMs " +
			"ORDER BY fact.observed_wall_time_ms, fact.logical_fact_id LIMIT :limit",
	)
	suspend fun effectiveLocalOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<AmbientCellEffectiveLocalRow>

	@Query(
		"SELECT fact.*, owner.observation_count AS owner_observation_count, " +
			"owner.registered_observation_count AS owner_registered_observation_count, " +
			"owner.gsm_count AS owner_gsm_count, owner.cdma_count AS owner_cdma_count, " +
			"owner.wcdma_count AS owner_wcdma_count, owner.tdscdma_count AS owner_tdscdma_count, " +
			"owner.lte_count AS owner_lte_count, owner.nr_count AS owner_nr_count, " +
			"owner.quality_unknown_count AS owner_quality_unknown_count, " +
			"owner.quality_none_or_unknown_count AS owner_quality_none_or_unknown_count, " +
			"owner.quality_poor_count AS owner_quality_poor_count, " +
			"owner.quality_moderate_count AS owner_quality_moderate_count, " +
			"owner.quality_good_count AS owner_quality_good_count, " +
			"owner.quality_great_count AS owner_quality_great_count " +
			"FROM ambient_cell_fact_revision AS fact " +
			"INNER JOIN ambient_cell_fact_cursor AS cursor ON cursor.writer_id = fact.writer_id " +
			"AND cursor.writer_version = fact.writer_version AND cursor.logical_fact_id = fact.logical_fact_id " +
			"AND cursor.latest_semantic_revision = fact.semantic_revision " +
			"LEFT JOIN ambient_cell_fact_revision AS owner ON owner.writer_id = fact.writer_id " +
			"AND owner.writer_version = fact.writer_version " +
			"AND owner.logical_fact_id = fact.aggregate_owner_logical_fact_id " +
			"AND owner.semantic_revision = fact.aggregate_owner_semantic_revision " +
			"WHERE fact.structural_epoch_day = :structuralEpochDay AND fact.stored_zone_id = :storedZoneId " +
			"ORDER BY fact.observed_wall_time_ms, fact.logical_fact_id LIMIT :limit",
	)
	suspend fun effectiveLocalForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<AmbientCellEffectiveLocalRow>

	@Query(
		"SELECT * FROM ambient_cell_gap WHERE gap_end_time_ms > :fromInclusiveMs " +
			"AND gap_start_time_ms < :toExclusiveMs ORDER BY gap_start_time_ms, gap_id LIMIT :limit",
	)
	suspend fun gapsOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<AmbientCellGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_fact AS fact WHERE observed_time_ms >= :fromInclusiveMs " +
			"AND observed_time_ms < :toExclusiveMs AND semantic_revision = " +
			"(SELECT MAX(state.semantic_revision) FROM imported_ambient_cell_fact AS state " +
			"WHERE state.fact_id = fact.fact_id) ORDER BY observed_time_ms, archive_id, fact_id LIMIT :limit",
	)
	suspend fun importedOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_fact AS fact WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId AND semantic_revision = " +
			"(SELECT MAX(state.semantic_revision) FROM imported_ambient_cell_fact AS state " +
			"WHERE state.fact_id = fact.fact_id) ORDER BY observed_time_ms, archive_id, fact_id LIMIT :limit",
	)
	suspend fun importedForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE end_time_ms > :fromInclusiveMs " +
			"AND start_time_ms < :toExclusiveMs ORDER BY start_time_ms, archive_id, gap_id LIMIT :limit",
	)
	suspend fun importedGapsOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId ORDER BY start_time_ms, archive_id, gap_id LIMIT :limit",
	)
	suspend fun importedGapsForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_fact WHERE archive_id = :archiveId " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedForArchive(
		archiveId: String,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_fact WHERE fact_id IN (:factIds) " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedFactRevisions(
		factIds: List<String>,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE archive_id = :archiveId " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapsForArchive(
		archiveId: String,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE gap_id IN (:gapIds) " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapsByIds(
		gapIds: List<String>,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportedFact(value: ImportedAmbientCellFactEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportedGap(value: ImportedAmbientCellGapEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportReceipt(value: ImportedAmbientCellReceiptEntity)

	@Query(
		"SELECT * FROM imported_ambient_cell_receipt WHERE import_job_id = :jobId " +
			"AND import_entry_key = :entryKey LIMIT 1",
	)
	suspend fun importReceipt(jobId: String, entryKey: String): ImportedAmbientCellReceiptEntity?

	@Query(
		"SELECT * FROM imported_ambient_cell_tombstone WHERE archive_id = :archiveId LIMIT 1",
	)
	suspend fun importTombstone(archiveId: String): ImportedAmbientCellTombstoneEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportTombstone(value: ImportedAmbientCellTombstoneEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportTombstones(values: List<ImportedAmbientCellTombstoneEntity>)

	@Query(
		"SELECT * FROM ambient_cell_deletion_marker WHERE collected_data_epoch = :collectedDataEpoch " +
			"ORDER BY deletion_generation DESC LIMIT 1",
	)
	suspend fun latestDeletionMarker(
		collectedDataEpoch: Long,
	): AmbientCellDeletionMarkerEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionMarker(value: AmbientCellDeletionMarkerEntity)

	@Query(
		"WITH RECURSIVE selected(logical_fact_id) AS (" +
			"SELECT logical_fact_id FROM ambient_cell_fact_revision " +
			"WHERE observed_wall_time_ms < :beforeMs AND retention_policy_id = :retentionPolicyId " +
			"UNION SELECT aggregate_owner_logical_fact_id FROM ambient_cell_fact_revision AS child " +
			"JOIN selected ON selected.logical_fact_id = child.logical_fact_id " +
			"WHERE child.aggregate_owner_logical_fact_id IS NOT NULL " +
			"UNION SELECT child.logical_fact_id FROM ambient_cell_fact_revision AS child " +
			"JOIN selected ON child.aggregate_owner_logical_fact_id = selected.logical_fact_id) " +
			"SELECT logical_fact_id FROM selected ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun localFactIdsBefore(
		beforeMs: Long,
		retentionPolicyId: String,
		limit: Int,
	): List<String>

	@Query("DELETE FROM ambient_cell_fact_cursor WHERE logical_fact_id IN (:logicalFactIds)")
	suspend fun deleteLocalCursors(logicalFactIds: List<String>): Int

	@Query("DELETE FROM ambient_cell_fact_revision WHERE logical_fact_id IN (:logicalFactIds)")
	suspend fun deleteLocalFacts(logicalFactIds: List<String>): Int

	@Query("DELETE FROM ambient_cell_gap WHERE gap_end_time_ms < :beforeMs")
	suspend fun deleteGapsBefore(beforeMs: Long): Int

	@Query(
		"WITH selected_fact AS (SELECT DISTINCT fact_id FROM imported_ambient_cell_fact " +
			"WHERE observed_time_ms < :beforeMs AND retention_policy_id = :retentionPolicyId) " +
			"SELECT archive_id FROM (" +
			"SELECT DISTINCT archive_id FROM imported_ambient_cell_fact " +
			"WHERE fact_id IN (SELECT fact_id FROM selected_fact) UNION " +
			"SELECT archive_id FROM imported_ambient_cell_gap WHERE end_time_ms < :beforeMs " +
			"AND retention_policy_id = :retentionPolicyId) ORDER BY archive_id LIMIT :limit",
	)
	suspend fun importedArchiveIdsBefore(
		beforeMs: Long,
		retentionPolicyId: String,
		limit: Int,
	): List<String>

	@Query("DELETE FROM imported_ambient_cell_fact WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportedFacts(archiveIds: List<String>): Int

	@Query("DELETE FROM imported_ambient_cell_gap WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportedGaps(archiveIds: List<String>): Int

	@Query("DELETE FROM imported_ambient_cell_receipt WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportReceipts(archiveIds: List<String>): Int

	@Query(
		"SELECT archive_id FROM (SELECT archive_id FROM imported_ambient_cell_fact UNION " +
			"SELECT archive_id FROM imported_ambient_cell_gap) ORDER BY archive_id LIMIT :limit",
	)
	suspend fun allImportedArchiveIds(limit: Int): List<String>

	@Query("SELECT DISTINCT archive_id FROM imported_ambient_cell_receipt ORDER BY archive_id LIMIT :limit")
	suspend fun allImportReceiptArchiveIds(limit: Int): List<String>

	@Query("DELETE FROM ambient_cell_fact_cursor")
	suspend fun deleteAllLocalCursors(): Int

	@Query("DELETE FROM ambient_cell_fact_revision")
	suspend fun deleteAllLocalFacts(): Int

	@Query("DELETE FROM ambient_cell_gap")
	suspend fun deleteAllGaps(): Int

	@Query("SELECT COUNT(*) FROM ambient_cell_fact_revision")
	suspend fun localFactCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_cell_fact")
	suspend fun importedFactCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_cell_gap")
	suspend fun importedGapCount(): Long
}
