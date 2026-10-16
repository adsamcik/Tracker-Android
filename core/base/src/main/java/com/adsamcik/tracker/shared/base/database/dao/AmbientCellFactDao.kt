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
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellTombstoneEntity

/**
 * Latest local Cell fact with its recorded aggregate counts; nullable owner counts expose a
 * missing source carrier.
 */
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
// This interface contains Room declarations only; keep this source’s SQL together.
@Suppress("LargeClass")
@Dao
interface AmbientCellFactDao {
	/** History discovery includes measured coverage; final display still selects real reading timestamps. */
	@Query("""SELECT fact.*, owner.observation_count AS owner_observation_count, owner.registered_observation_count AS owner_registered_observation_count, owner.gsm_count AS owner_gsm_count, owner.cdma_count AS owner_cdma_count, owner.wcdma_count AS owner_wcdma_count, owner.tdscdma_count AS owner_tdscdma_count, owner.lte_count AS owner_lte_count, owner.nr_count AS owner_nr_count, owner.quality_unknown_count AS owner_quality_unknown_count, owner.quality_none_or_unknown_count AS owner_quality_none_or_unknown_count, owner.quality_poor_count AS owner_quality_poor_count, owner.quality_moderate_count AS owner_quality_moderate_count, owner.quality_good_count AS owner_quality_good_count, owner.quality_great_count AS owner_quality_great_count
		FROM ambient_cell_fact_revision fact
		JOIN ambient_cell_fact_cursor cursor ON cursor.writer_id = fact.writer_id AND cursor.writer_version = fact.writer_version
		AND cursor.logical_fact_id = fact.logical_fact_id AND cursor.latest_semantic_revision = fact.semantic_revision
		LEFT JOIN ambient_cell_fact_revision owner ON owner.writer_id = fact.writer_id AND owner.writer_version = fact.writer_version
		AND owner.logical_fact_id = fact.aggregate_owner_logical_fact_id AND owner.semantic_revision = fact.aggregate_owner_semantic_revision
		WHERE MIN(fact.coverage_start_time_ms, fact.observed_wall_time_ms -
			((fact.observed_elapsed_nanos - fact.observed_interval_start_nanos) / 1000000 +
			CASE WHEN (fact.observed_elapsed_nanos - fact.observed_interval_start_nanos) % 1000000 = 0
				THEN 0 ELSE 1 END)) -
			fact.wall_time_uncertainty_ms < :toExclusiveMs
		AND (fact.observed_wall_time_ms >= :fromInclusiveMs OR
		(fact.observed_wall_time_ms < :fromInclusiveMs AND fact.wall_time_uncertainty_ms >= :fromInclusiveMs - fact.observed_wall_time_ms))
		ORDER BY fact.observed_wall_time_ms, fact.logical_fact_id LIMIT :limit""")
	suspend fun effectiveLocalOverObservationWindow(fromInclusiveMs: Long, toExclusiveMs: Long, limit: Int):
		List<AmbientCellEffectiveLocalRow>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `coverage_start_time_ms <
	 * :toExclusiveMs`, `latest_possible_time_ms >= :fromInclusiveMs`. Orders by `observed_time_ms,
	 * archive_id, fact_id`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query("""SELECT * FROM imported_ambient_cell_fact fact WHERE coverage_start_time_ms < :toExclusiveMs
		AND latest_possible_time_ms >= :fromInclusiveMs AND semantic_revision =
		(SELECT MAX(n.semantic_revision) FROM imported_ambient_cell_fact n WHERE n.fact_id = fact.fact_id)
		ORDER BY observed_time_ms, archive_id, fact_id LIMIT :limit""")
	suspend fun importedOverObservationWindow(fromInclusiveMs: Long, toExclusiveMs: Long, limit: Int):
		List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `ambient_cell_fact_cursor` rows. Query conditions include `logical_fact_id IN (:ids)`.
	 * Orders by `logical_fact_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_fact_cursor WHERE logical_fact_id IN (:ids) ORDER BY logical_fact_id LIMIT :limit")
	suspend fun retainedCursors(ids: List<String>, limit: Int): List<AmbientCellFactCursorEntity>

	/**
	 * Reads `imported_ambient_cell_receipt` rows. Query conditions include `archive_id IN (:ids)`.
	 * Orders by `archive_id, import_job_id, import_entry_key`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_cell_receipt WHERE archive_id IN (:ids) " +
		"ORDER BY archive_id, import_job_id, import_entry_key LIMIT :limit")
	suspend fun retainedImportReceipts(ids: List<String>, limit: Int): List<ImportedAmbientCellReceiptEntity>

	/**
	 * Reads `imported_ambient_cell_tombstone` rows. Query conditions include `archive_id IN (:ids)`.
	 * Orders by `archive_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_cell_tombstone WHERE archive_id IN (:ids) ORDER BY archive_id LIMIT :limit")
	suspend fun retainedImportTombstones(ids: List<String>, limit: Int): List<ImportedAmbientCellTombstoneEntity>

	/**
	 * Reads `ambient_cell_retention_authority` rows. Query conditions include `scope = :scope`,
	 * `approval_revision IN (:revisions)`. Orders by `approval_revision`. Returns at most `limit`
	 * rows.
	 */
	@Query("SELECT * FROM ambient_cell_retention_authority WHERE scope = :scope AND approval_revision IN (:revisions) " +
		"ORDER BY approval_revision LIMIT :limit")
	suspend fun retainedApprovals(scope: String, revisions: List<Long>,
        limit: Int): List<AmbientCellRetentionAuthorityEntity>

	/**
	 * Inserts [AmbientCellAuthorityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAuthority(value: AmbientCellAuthorityEntity)

	/**
	 * Returns the greatest `authority_revision` in `ambient_cell_authority`. Returns 0 when no saved
	 * value exists.
	 */
	@Query("SELECT COALESCE(MAX(authority_revision), 0) FROM ambient_cell_authority")
	suspend fun maximumAuthorityRevision(): Long

	/**
	 * Reads `ambient_cell_authority` rows. Orders by `authority_revision DESC`. Returns at most 1
	 * row. Returns null when no row matches.
	 */
	@Query("SELECT * FROM ambient_cell_authority ORDER BY authority_revision DESC LIMIT 1")
	suspend fun latestAuthority(): AmbientCellAuthorityEntity?

	/**
	 * Reads `ambient_cell_authority` rows. Query conditions include `effective_boot_id = :bootId`,
	 * `effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos`. Orders by
	 * `effective_elapsed_realtime_nanos DESC, authority_revision DESC`. Returns at most 1 row.
	 * Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_authority WHERE effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authority_revision DESC LIMIT 1",
	)
	suspend fun authorityAt(
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): AmbientCellAuthorityEntity?

	/**
	 * Inserts [AmbientCellRetentionAuthorityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRetentionAuthority(value: AmbientCellRetentionAuthorityEntity)

	/**
	 * Reads `ambient_cell_retention_authority` rows. Query conditions include `scope = :scope`.
	 * Orders by `approval_revision DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision DESC LIMIT 1",
	)
	suspend fun latestRetentionAuthority(scope: String): AmbientCellRetentionAuthorityEntity?

	/**
	 * Reads `ambient_cell_retention_authority` rows. Query conditions include `scope = :scope`.
	 * Orders by `approval_revision ASC`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision ASC LIMIT :limit",
	)
	suspend fun retentionAuthorities(
		scope: String,
		limit: Int,
	): List<AmbientCellRetentionAuthorityEntity>

	/**
	 * Reads `ambient_cell_retention_authority` rows. Query conditions include `scope = :scope`,
	 * `effective_boot_id = :bootId`, `effective_elapsed_realtime_nanos <=
	 * :observedElapsedRealtimeNanos`. Orders by `effective_elapsed_realtime_nanos DESC,
	 * approval_revision DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_retention_authority WHERE scope = :scope " +
			"AND effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, approval_revision DESC LIMIT 1",
	)
	suspend fun retentionAuthorityAt(
		scope: String,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): AmbientCellRetentionAuthorityEntity?

	/**
	 * Returns the greatest `approval_revision` in `ambient_cell_retention_authority`. Query
	 * conditions include `scope = :scope`. Returns 0 when no saved value exists.
	 */
	@Query(
		"SELECT COALESCE(MAX(approval_revision), 0) FROM ambient_cell_retention_authority " +
			"WHERE scope = :scope",
	)
	suspend fun maximumRetentionApprovalRevision(scope: String): Long

	/**
	 * Updates `source_demand` rows matching the supplied scope. Query conditions include `demand_id =
	 * :demandId`, `consumer_id = :consumerId`, `source_kind = :sourceKind`, `purpose =
	 * 'AMBIENT_PRODUCT'`. Returns the affected row count.
	 */
	@Query(
		"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE demand_id = :demandId AND consumer_id = :consumerId " +
			"AND source_kind = :sourceKind AND purpose = 'AMBIENT_PRODUCT' " +
			"AND source_policy_revision = :sourcePolicyRevision AND consent_epoch = :consentEpoch " +
			"AND status IN ('ACTIVE','RETIRING','BLOCKED')",
	)
	suspend fun retireExactAmbientDemand(
		demandId: String,
		consumerId: String,
		sourceKind: Int,
		sourcePolicyRevision: Long,
		consentEpoch: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/**
	 * Inserts [AmbientCellFactRevisionEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(value: AmbientCellFactRevisionEntity): Long

	/**
	 * Inserts [AmbientCellFactCursorEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(value: AmbientCellFactCursorEntity): Long

	/**
	 * Reads `ambient_cell_fact_cursor` rows. Query conditions include `writer_id = :writerId`,
	 * `writer_version = :writerVersion`, `logical_fact_id = :logicalFactId`. Returns at most 1 row.
	 * Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_fact_cursor WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): AmbientCellFactCursorEntity?

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `writer_id = :writerId`,
	 * `writer_version = :writerVersion`, `logical_fact_id = :logicalFactId`, `semantic_revision =
	 * :semanticRevision`. Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Updates `ambient_cell_fact_cursor` rows matching the supplied scope. Query conditions include
	 * `writer_id = :writerId`, `writer_version = :writerVersion`, `logical_fact_id = :logicalFactId`,
	 * `latest_semantic_revision = :expectedSemanticRevision`. Returns the affected row count.
	 */
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

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `fact.fact_kind =
	 * '${AmbientCellFactRevisionEntity.FACT_KIND_AGGREGATE}'`, `fact.ambient_consent_epoch =
	 * :ambientConsentEpoch`, `fact.collected_data_epoch = :collectedDataEpoch`,
	 * `fact.scope_deletion_generation = :scopeDeletionGeneration`. Orders by
	 * `fact.source_admission_ordinal DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Inserts [AmbientCellGapEntity] rows. Existing conflicting rows remain unchanged. An ignored
	 * insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertGap(value: AmbientCellGapEntity): Long

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `fact.observed_wall_time_ms
	 * >= :fromInclusiveMs`, `fact.observed_wall_time_ms < :toExclusiveMs`. Orders by
	 * `fact.observed_wall_time_ms, fact.logical_fact_id`. Returns at most `limit` rows. Timestamp
	 * bounds use UTC milliseconds.
	 */
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

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `fact.structural_epoch_day =
	 * :structuralEpochDay`, `fact.stored_zone_id = :storedZoneId`. Orders by
	 * `fact.observed_wall_time_ms, fact.logical_fact_id`. Returns at most `limit` rows.
	 */
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

	/**
	 * Reads `ambient_cell_gap` rows. Query conditions include `gap_end_time_ms > :fromInclusiveMs`,
	 * `gap_start_time_ms < :toExclusiveMs`. Orders by `gap_start_time_ms, gap_id`. Returns at most
	 * `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query(
		"SELECT * FROM ambient_cell_gap WHERE gap_end_time_ms > :fromInclusiveMs " +
			"AND gap_start_time_ms < :toExclusiveMs ORDER BY gap_start_time_ms, gap_id LIMIT :limit",
	)
	suspend fun gapsOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<AmbientCellGapEntity>

	/**
	 * Reads `ambient_cell_gap` rows. Query conditions include `structural_epoch_day =
	 * :structuralEpochDay`, `stored_zone_id = :storedZoneId`. Orders by `gap_start_time_ms, gap_id`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_gap WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId ORDER BY gap_start_time_ms, gap_id LIMIT :limit",
	)
	suspend fun gapsForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<AmbientCellGapEntity>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `observed_time_ms >=
	 * :fromInclusiveMs`, `observed_time_ms < :toExclusiveMs`. Orders by `observed_time_ms,
	 * archive_id, fact_id`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
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

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `structural_epoch_day =
	 * :structuralEpochDay`, `stored_zone_id = :storedZoneId`. Orders by `observed_time_ms,
	 * archive_id, fact_id`. Returns at most `limit` rows.
	 */
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

	/**
	 * Reads `imported_ambient_cell_gap` rows. Query conditions include `end_time_ms >
	 * :fromInclusiveMs`, `start_time_ms < :toExclusiveMs`. Orders by `start_time_ms, archive_id,
	 * gap_id`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE end_time_ms > :fromInclusiveMs " +
			"AND start_time_ms < :toExclusiveMs ORDER BY start_time_ms, archive_id, gap_id LIMIT :limit",
	)
	suspend fun importedGapsOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	/**
	 * Reads `imported_ambient_cell_gap` rows. Query conditions include `structural_epoch_day =
	 * :structuralEpochDay`, `stored_zone_id = :storedZoneId`. Orders by `start_time_ms, archive_id,
	 * gap_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId ORDER BY start_time_ms, archive_id, gap_id LIMIT :limit",
	)
	suspend fun importedGapsForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `archive_id = :archiveId`.
	 * Orders by `fact_id, semantic_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_fact WHERE archive_id = :archiveId " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedForArchive(
		archiveId: String,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `fact_id IN (:factIds)`.
	 * Orders by `fact_id, semantic_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_fact WHERE fact_id IN (:factIds) " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedFactRevisions(
		factIds: List<String>,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `imported_ambient_cell_gap` rows. Query conditions include `archive_id = :archiveId`.
	 * Orders by `gap_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE archive_id = :archiveId " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapsForArchive(
		archiveId: String,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	/**
	 * Reads `imported_ambient_cell_gap` rows. Query conditions include `gap_id IN (:gapIds)`. Orders
	 * by `gap_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE gap_id IN (:gapIds) " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapsByIds(
		gapIds: List<String>,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	/**
	 * Inserts [ImportedAmbientCellFactEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportedFact(value: ImportedAmbientCellFactEntity)

	/**
	 * Inserts [ImportedAmbientCellGapEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportedGap(value: ImportedAmbientCellGapEntity)

	/**
	 * Inserts [ImportedAmbientCellReceiptEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportReceipt(value: ImportedAmbientCellReceiptEntity)

	/**
	 * Reads `imported_ambient_cell_receipt` rows. Query conditions include `import_job_id = :jobId`,
	 * `import_entry_key = :entryKey`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_receipt WHERE import_job_id = :jobId " +
			"AND import_entry_key = :entryKey LIMIT 1",
	)
	suspend fun importReceipt(jobId: String, entryKey: String): ImportedAmbientCellReceiptEntity?

	/**
	 * Reads `imported_ambient_cell_tombstone` rows. Query conditions include `archive_id =
	 * :archiveId`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_tombstone WHERE archive_id = :archiveId LIMIT 1",
	)
	suspend fun importTombstone(archiveId: String): ImportedAmbientCellTombstoneEntity?

	/**
	 * Inserts [ImportedAmbientCellTombstoneEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportTombstone(value: ImportedAmbientCellTombstoneEntity)

	/**
	 * Inserts [ImportedAmbientCellTombstoneEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportTombstones(values: List<ImportedAmbientCellTombstoneEntity>)

	/**
	 * Inserts [AmbientCellReplayFootprintEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertReplayFootprints(values: List<AmbientCellReplayFootprintEntity>): List<Long>

	/**
	 * Reads `ambient_cell_replay_footprint` rows. Query conditions include `footprint_kind = :kind`,
	 * `identity_digest = :identityDigest`, `semantic_revision = :semanticRevision`. Returns at most 1
	 * row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_replay_footprint WHERE footprint_kind = :kind " +
			"AND identity_digest = :identityDigest AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun replayFootprint(
		kind: String,
		identityDigest: String,
		semanticRevision: Long,
	): AmbientCellReplayFootprintEntity?

	/**
	 * Reads `ambient_cell_replay_footprint` rows. Query conditions include `identity_digest IN
	 * (:identityDigests)`. Orders by `footprint_kind, identity_digest, semantic_revision`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_replay_footprint " +
			"WHERE identity_digest IN (:identityDigests) " +
			"ORDER BY footprint_kind, identity_digest, semantic_revision LIMIT :limit",
	)
	suspend fun replayFootprintsByIdentityDigests(
		identityDigests: List<String>,
		limit: Int,
	): List<AmbientCellReplayFootprintEntity>

	/**
	 * Reads `ambient_cell_deletion_marker` rows. Query conditions include `collected_data_epoch =
	 * :collectedDataEpoch`. Orders by `deletion_generation DESC`. Returns at most 1 row. Returns null
	 * when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_deletion_marker WHERE collected_data_epoch = :collectedDataEpoch " +
			"ORDER BY deletion_generation DESC LIMIT 1",
	)
	suspend fun latestDeletionMarker(
		collectedDataEpoch: Long,
	): AmbientCellDeletionMarkerEntity?

	/**
	 * Inserts [AmbientCellDeletionMarkerEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionMarker(value: AmbientCellDeletionMarkerEntity)

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `latest.observed_wall_time_ms
	 * < :beforeMs`. Orders by `logical_fact_id`. Returns at most `limit` rows. Timestamp bounds use
	 * UTC milliseconds.
	 */
	@Query(
		"WITH RECURSIVE selected(logical_fact_id) AS (" +
			"SELECT latest.logical_fact_id FROM ambient_cell_fact_revision AS latest " +
			"WHERE latest.semantic_revision = (" +
			"SELECT MAX(history.semantic_revision) FROM ambient_cell_fact_revision AS history " +
			"WHERE history.logical_fact_id = latest.logical_fact_id) " +
			"AND latest.observed_wall_time_ms < :beforeMs " +
			"UNION SELECT CASE WHEN child.logical_fact_id = selected.logical_fact_id " +
			"THEN child.aggregate_owner_logical_fact_id ELSE child.logical_fact_id END " +
			"FROM selected JOIN ambient_cell_fact_revision AS child " +
			"ON child.logical_fact_id = selected.logical_fact_id " +
			"OR child.aggregate_owner_logical_fact_id = selected.logical_fact_id " +
			"WHERE child.aggregate_owner_logical_fact_id IS NOT NULL) " +
			"SELECT logical_fact_id FROM selected ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun localFactIdsBefore(
		beforeMs: Long,
		limit: Int,
	): List<String>

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `logical_fact_id IN
	 * (:logicalFactIds)`. Orders by `logical_fact_id, semantic_revision`. Returns at most `limit`
	 * rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_fact_revision WHERE logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun localFactLineages(
		logicalFactIds: List<String>,
		limit: Int,
	): List<AmbientCellFactRevisionEntity>

	/**
	 * Reads `ambient_cell_fact_revision` rows. Query conditions include `fact.logical_fact_id IN
	 * (:logicalFactIds)`. Orders by `fact.logical_fact_id, fact.semantic_revision`. Returns at most
	 * `limit` rows.
	 */
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
			"LEFT JOIN ambient_cell_fact_revision AS owner ON owner.writer_id = fact.writer_id " +
			"AND owner.writer_version = fact.writer_version " +
			"AND owner.logical_fact_id = fact.aggregate_owner_logical_fact_id " +
			"AND owner.semantic_revision = fact.aggregate_owner_semantic_revision " +
			"WHERE fact.logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY fact.logical_fact_id, fact.semantic_revision LIMIT :limit",
	)
	suspend fun effectiveLocalLineages(
		logicalFactIds: List<String>,
		limit: Int,
	): List<AmbientCellEffectiveLocalRow>

	/**
	 * Reads `ambient_cell_fact_revision` rows. Orders by `logical_fact_id, semantic_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_fact_revision ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun allLocalFactRevisions(limit: Int): List<AmbientCellFactRevisionEntity>

	/**
	 * Reads `ambient_cell_gap` rows. Orders by `gap_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_gap ORDER BY gap_id LIMIT :limit")
	suspend fun allLocalGaps(limit: Int): List<AmbientCellGapEntity>

	/**
	 * Deletes `ambient_cell_fact_cursor` rows matching the supplied scope. Query conditions include
	 * `logical_fact_id IN (:logicalFactIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_fact_cursor WHERE logical_fact_id IN (:logicalFactIds)")
	suspend fun deleteLocalCursors(logicalFactIds: List<String>): Int

	/**
	 * Deletes `ambient_cell_fact_revision` rows matching the supplied scope. Query conditions include
	 * `logical_fact_id IN (:logicalFactIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_fact_revision WHERE logical_fact_id IN (:logicalFactIds)")
	suspend fun deleteLocalFacts(logicalFactIds: List<String>): Int

	/**
	 * Deletes `ambient_cell_gap` rows matching the supplied scope. Query conditions include
	 * `gap_end_time_ms < :beforeMs`. Returns the affected row count. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query("DELETE FROM ambient_cell_gap WHERE gap_end_time_ms < :beforeMs")
	suspend fun deleteGapsBefore(beforeMs: Long): Int

	/**
	 * Reads `ambient_cell_gap` rows. Query conditions include `gap_end_time_ms < :beforeMs`. Orders
	 * by `gap_id`. Returns at most `limit` rows. Timestamp bounds use UTC milliseconds.
	 */
	@Query(
		"SELECT * FROM ambient_cell_gap WHERE gap_end_time_ms < :beforeMs " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun localGapsBefore(
		beforeMs: Long,
		limit: Int,
	): List<AmbientCellGapEntity>

	/**
	 * Deletes `ambient_cell_gap` rows matching the supplied scope. Query conditions include `gap_id
	 * IN (:gapIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_gap WHERE gap_id IN (:gapIds)")
	suspend fun deleteLocalGapsByIdentity(gapIds: List<String>): Int

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `latest.observed_time_ms <
	 * :beforeMs`. Orders by `latest.fact_id`. Returns at most `limit` rows. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query(
		"SELECT latest.fact_id FROM imported_ambient_cell_fact AS latest " +
			"WHERE latest.semantic_revision = (" +
			"SELECT MAX(history.semantic_revision) FROM imported_ambient_cell_fact AS history " +
			"WHERE history.fact_id = latest.fact_id) " +
			"AND latest.observed_time_ms < :beforeMs " +
			"ORDER BY latest.fact_id LIMIT :limit",
	)
	suspend fun importedFactIdsBefore(
		beforeMs: Long,
		limit: Int,
	): List<String>

	/**
	 * Reads distinct `gap_id` values from `imported_ambient_cell_gap`. Query conditions include
	 * `end_time_ms < :beforeMs`. Orders by `gap_id`. Returns at most `limit` rows. Timestamp bounds
	 * use UTC milliseconds.
	 */
	@Query(
		"SELECT DISTINCT gap_id FROM imported_ambient_cell_gap WHERE end_time_ms < :beforeMs " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapIdsBefore(
		beforeMs: Long,
		limit: Int,
	): List<String>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `fact_id IN (:factIds)`.
	 * Orders by `fact_id, semantic_revision`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_fact WHERE fact_id IN (:factIds) " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedFactLineages(
		factIds: List<String>,
		limit: Int,
	): List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `imported_ambient_cell_gap` rows. Query conditions include `gap_id IN (:gapIds)`. Orders
	 * by `gap_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap WHERE gap_id IN (:gapIds) " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapRows(
		gapIds: List<String>,
		limit: Int,
	): List<ImportedAmbientCellGapEntity>

	/**
	 * Deletes `imported_ambient_cell_fact` rows matching the supplied scope. Query conditions include
	 * `fact_id IN (:factIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_fact WHERE fact_id IN (:factIds)")
	suspend fun deleteImportedFactsByIdentity(factIds: List<String>): Int

	/**
	 * Deletes `imported_ambient_cell_gap` rows matching the supplied scope. Query conditions include
	 * `gap_id IN (:gapIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_gap WHERE gap_id IN (:gapIds)")
	suspend fun deleteImportedGapsByIdentity(gapIds: List<String>): Int

	/**
	 * Reads distinct `receipt.archive_id` values from `imported_ambient_cell_receipt`. Orders by
	 * `receipt.archive_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT DISTINCT receipt.archive_id FROM imported_ambient_cell_receipt AS receipt " +
			"WHERE NOT EXISTS (SELECT 1 FROM imported_ambient_cell_fact AS fact " +
			"WHERE fact.archive_id = receipt.archive_id) " +
			"AND NOT EXISTS (SELECT 1 FROM imported_ambient_cell_gap AS gap " +
			"WHERE gap.archive_id = receipt.archive_id) ORDER BY receipt.archive_id LIMIT :limit",
	)
	suspend fun emptyImportedArchiveIds(limit: Int): List<String>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Orders by `archive_id, fact_id, semantic_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_fact ORDER BY archive_id, fact_id, semantic_revision " +
			"LIMIT :limit",
	)
	suspend fun allImportedFacts(limit: Int): List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `imported_ambient_cell_gap` rows. Orders by `archive_id, gap_id`. Returns at most `limit`
	 * rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap ORDER BY archive_id, gap_id LIMIT :limit",
	)
	suspend fun allImportedGaps(limit: Int): List<ImportedAmbientCellGapEntity>

	/**
	 * Deletes `imported_ambient_cell_fact` rows matching the supplied scope. Query conditions include
	 * `archive_id IN (:archiveIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_fact WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportedFacts(archiveIds: List<String>): Int

	/**
	 * Deletes `imported_ambient_cell_gap` rows matching the supplied scope. Query conditions include
	 * `archive_id IN (:archiveIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_gap WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportedGaps(archiveIds: List<String>): Int

	/**
	 * Deletes `imported_ambient_cell_receipt` rows matching the supplied scope. Query conditions
	 * include `archive_id IN (:archiveIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_receipt WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportReceipts(archiveIds: List<String>): Int

	/**
	 * Reads distinct imported archive IDs from the saved source facts and gaps, ordered by archive ID
	 * within the requested limit.
	 */
	@Query(
		"SELECT archive_id FROM (SELECT archive_id FROM imported_ambient_cell_fact UNION " +
			"SELECT archive_id FROM imported_ambient_cell_gap) ORDER BY archive_id LIMIT :limit",
	)
	suspend fun allImportedArchiveIds(limit: Int): List<String>

	/**
	 * Reads distinct `archive_id` values from `imported_ambient_cell_receipt`. Orders by
	 * `archive_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT DISTINCT archive_id FROM imported_ambient_cell_receipt ORDER BY archive_id LIMIT :limit")
	suspend fun allImportReceiptArchiveIds(limit: Int): List<String>

	/**
	 * Deletes `ambient_cell_fact_cursor` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_fact_cursor")
	suspend fun deleteAllLocalCursors(): Int

	/**
	 * Deletes `ambient_cell_fact_revision` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_fact_revision")
	suspend fun deleteAllLocalFacts(): Int

	/**
	 * Deletes `ambient_cell_gap` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_gap")
	suspend fun deleteAllGaps(): Int

	/**
	 * Counts rows in `ambient_cell_fact_revision`.
	 */
	@Query("SELECT COUNT(*) FROM ambient_cell_fact_revision")
	suspend fun localFactCount(): Long

	/**
	 * Counts rows in `ambient_cell_fact_cursor`.
	 */
	@Query("SELECT COUNT(*) FROM ambient_cell_fact_cursor")
	suspend fun localCursorCount(): Long

	/**
	 * Counts rows in `imported_ambient_cell_fact`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_cell_fact")
	suspend fun importedFactCount(): Long

	/**
	 * Counts rows in `imported_ambient_cell_gap`.
	 */
	@Query("SELECT COUNT(*) FROM imported_ambient_cell_gap")
	suspend fun importedGapCount(): Long

	/**
	 * Deletes `ambient_cell_authority` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_authority")
	suspend fun deleteAllAuthorities(): Int

	/**
	 * Deletes `ambient_cell_retention_authority` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_retention_authority")
	suspend fun deleteAllRetentionAuthorities(): Int

	/**
	 * Reads `ambient_cell_authority` rows. Orders by `authority_revision DESC`. Returns at most 1
	 * row. Returns null when no row matches.
	 */
	@Query("SELECT * FROM ambient_cell_authority ORDER BY authority_revision DESC LIMIT 1")
	fun latestAuthorityForFullClear(): AmbientCellAuthorityEntity?

	/**
	 * Reads `ambient_cell_deletion_marker` rows. Query conditions include `collected_data_epoch =
	 * :collectedDataEpoch`. Orders by `deletion_generation DESC`. Returns at most 1 row. Returns null
	 * when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_cell_deletion_marker WHERE collected_data_epoch = :collectedDataEpoch " +
			"ORDER BY deletion_generation DESC LIMIT 1",
	)
	fun latestDeletionMarkerForFullClear(
		collectedDataEpoch: Long,
	): AmbientCellDeletionMarkerEntity?

	/**
	 * Counts rows in `ambient_cell_fact_cursor`.
	 */
	@Query("SELECT COUNT(*) FROM ambient_cell_fact_cursor")
	fun localCursorCountForFullClear(): Long

	/**
	 * Reads `ambient_cell_fact_revision` rows. Orders by `logical_fact_id, semantic_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_fact_revision ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	fun allLocalFactRevisionsForFullClear(limit: Int): List<AmbientCellFactRevisionEntity>

	/**
	 * Reads `ambient_cell_gap` rows. Orders by `gap_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_gap ORDER BY gap_id LIMIT :limit")
	fun allLocalGapsForFullClear(limit: Int): List<AmbientCellGapEntity>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Orders by `archive_id, fact_id, semantic_revision`.
	 * Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_fact ORDER BY archive_id, fact_id, semantic_revision " +
			"LIMIT :limit",
	)
	fun allImportedFactsForFullClear(limit: Int): List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `imported_ambient_cell_gap` rows. Orders by `archive_id, gap_id`. Returns at most `limit`
	 * rows.
	 */
	@Query(
		"SELECT * FROM imported_ambient_cell_gap ORDER BY archive_id, gap_id LIMIT :limit",
	)
	fun allImportedGapsForFullClear(limit: Int): List<ImportedAmbientCellGapEntity>

	/**
	 * Reads distinct imported archive IDs from source facts and gaps for full-clear cleanup, ordered
	 * by archive ID within the requested limit.
	 */
	@Query(
		"SELECT archive_id FROM (SELECT archive_id FROM imported_ambient_cell_fact UNION " +
			"SELECT archive_id FROM imported_ambient_cell_gap) ORDER BY archive_id LIMIT :limit",
	)
	fun allImportedArchiveIdsForFullClear(limit: Int): List<String>

	/**
	 * Reads distinct `archive_id` values from `imported_ambient_cell_receipt`. Orders by
	 * `archive_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT DISTINCT archive_id FROM imported_ambient_cell_receipt ORDER BY archive_id LIMIT :limit")
	fun allImportReceiptArchiveIdsForFullClear(limit: Int): List<String>

	/**
	 * Reads `ambient_cell_replay_footprint` rows. Query conditions include `identity_digest IN
	 * (:identityDigests)`. Orders by `footprint_kind, identity_digest, semantic_revision`. Returns at
	 * most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_cell_replay_footprint " +
			"WHERE identity_digest IN (:identityDigests) " +
			"ORDER BY footprint_kind, identity_digest, semantic_revision LIMIT :limit",
	)
	fun replayFootprintsByIdentityDigestsForFullClear(
		identityDigests: List<String>,
		limit: Int,
	): List<AmbientCellReplayFootprintEntity>

	/**
	 * Inserts [AmbientCellReplayFootprintEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	fun insertReplayFootprintsForFullClear(
		values: List<AmbientCellReplayFootprintEntity>,
	): List<Long>

	/**
	 * Inserts [AmbientCellDeletionMarkerEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	fun insertDeletionMarkerForFullClear(value: AmbientCellDeletionMarkerEntity)

	/**
	 * Inserts [ImportedAmbientCellTombstoneEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	fun insertImportTombstonesForFullClear(values: List<ImportedAmbientCellTombstoneEntity>)

	/**
	 * Deletes `ambient_cell_fact_cursor` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_fact_cursor")
	fun deleteAllLocalCursorsForFullClear(): Int

	/**
	 * Deletes `ambient_cell_fact_revision` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_fact_revision")
	fun deleteAllLocalFactsForFullClear(): Int

	/**
	 * Deletes `ambient_cell_gap` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_gap")
	fun deleteAllGapsForFullClear(): Int

	/**
	 * Deletes `imported_ambient_cell_receipt` rows matching the supplied scope. Query conditions
	 * include `archive_id IN (:archiveIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_receipt WHERE archive_id IN (:archiveIds)")
	fun deleteImportReceiptsForFullClear(archiveIds: List<String>): Int

	/**
	 * Deletes `imported_ambient_cell_gap` rows matching the supplied scope. Query conditions include
	 * `archive_id IN (:archiveIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_gap WHERE archive_id IN (:archiveIds)")
	fun deleteImportedGapsForFullClear(archiveIds: List<String>): Int

	/**
	 * Deletes `imported_ambient_cell_fact` rows matching the supplied scope. Query conditions include
	 * `archive_id IN (:archiveIds)`. Returns the affected row count.
	 */
	@Query("DELETE FROM imported_ambient_cell_fact WHERE archive_id IN (:archiveIds)")
	fun deleteImportedFactsForFullClear(archiveIds: List<String>): Int

	/**
	 * Deletes `ambient_cell_authority` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_authority")
	fun deleteAllAuthoritiesForFullClear(): Int

	/**
	 * Deletes `ambient_cell_retention_authority` rows. Returns the affected row count.
	 */
	@Query("DELETE FROM ambient_cell_retention_authority")
	fun deleteAllRetentionAuthoritiesForFullClear(): Int

	/** Exact current parent selection for measured History, independent of the size of a day. */
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
			"WHERE fact.logical_fact_id IN (:factIds) " +
			"ORDER BY fact.observed_wall_time_ms, fact.logical_fact_id LIMIT :limit",
	)
	suspend fun effectiveLocalByFactIds(factIds: List<String>, limit: Int): List<AmbientCellEffectiveLocalRow>

	/**
	 * Reads `imported_ambient_cell_fact` rows. Query conditions include `f.fact_id IN (:factIds)`.
	 * Orders by `f.fact_id, f.archive_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM imported_ambient_cell_fact f WHERE f.fact_id IN (:factIds) " +
		"AND f.semantic_revision = (SELECT MAX(n.semantic_revision) FROM imported_ambient_cell_fact n " +
		"WHERE n.fact_id = f.fact_id) ORDER BY f.fact_id, f.archive_id LIMIT :limit")
	suspend fun effectiveImportedByFactIds(factIds: List<String>, limit: Int): List<ImportedAmbientCellFactEntity>

	/**
	 * Reads `ambient_cell_gap` rows. Query conditions include `gap_id IN (:gapIds)`. Orders by
	 * `gap_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_cell_gap WHERE gap_id IN (:gapIds) ORDER BY gap_id LIMIT :limit")
	suspend fun gapsByIds(gapIds: List<String>, limit: Int): List<AmbientCellGapEntity>
}
