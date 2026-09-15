package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiDeletionMarkerEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiReplayFootprintEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiFactEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiGapEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiTombstoneEntity

data class AmbientWifiEffectiveLocalRow(
	@Embedded val fact: AmbientWifiFactRevisionEntity,
	@ColumnInfo(name = "owner_observation_count") val ownerObservationCount: Int?,
	@ColumnInfo(name = "owner_two_point_four_ghz_count") val ownerTwoPointFourGhzCount: Int?,
	@ColumnInfo(name = "owner_five_ghz_count") val ownerFiveGhzCount: Int?,
	@ColumnInfo(name = "owner_six_ghz_count") val ownerSixGhzCount: Int?,
	@ColumnInfo(name = "owner_other_band_count") val ownerOtherBandCount: Int?,
	@ColumnInfo(name = "owner_strongest_signal_dbm") val ownerStrongestSignalDbm: Int?,
	@ColumnInfo(name = "owner_weakest_signal_dbm") val ownerWeakestSignalDbm: Int?,
	@ColumnInfo(name = "owner_signal_sum_dbm") val ownerSignalSumDbm: Long?,
)

/** Bounded source-local storage boundary for Ambient Wi-Fi product evidence. */
@Dao
interface AmbientWifiFactDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAuthority(value: AmbientWifiAuthorityEntity)

	@Query("SELECT COALESCE(MAX(authority_revision), 0) FROM ambient_wifi_authority")
	suspend fun maximumAuthorityRevision(): Long

	@Query("SELECT * FROM ambient_wifi_authority ORDER BY authority_revision DESC LIMIT 1")
	suspend fun latestAuthority(): AmbientWifiAuthorityEntity?

	@Query(
		"SELECT * FROM ambient_wifi_authority WHERE effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authority_revision DESC LIMIT 1",
	)
	suspend fun authorityAt(
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): AmbientWifiAuthorityEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRetentionAuthority(value: AmbientWifiRetentionAuthorityEntity)

	@Query(
		"SELECT * FROM ambient_wifi_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision DESC LIMIT 1",
	)
	suspend fun latestRetentionAuthority(scope: String): AmbientWifiRetentionAuthorityEntity?

	@Query(
		"SELECT * FROM ambient_wifi_retention_authority WHERE scope = :scope " +
			"AND effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, approval_revision DESC LIMIT 1",
	)
	suspend fun retentionAuthorityAt(
		scope: String,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): AmbientWifiRetentionAuthorityEntity?

	@Query(
		"SELECT COALESCE(MAX(approval_revision), 0) FROM ambient_wifi_retention_authority " +
			"WHERE scope = :scope",
	)
	suspend fun maximumRetentionApprovalRevision(scope: String): Long

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

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(value: AmbientWifiFactRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(value: AmbientWifiFactCursorEntity): Long

	@Query(
		"SELECT * FROM ambient_wifi_fact_cursor WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): AmbientWifiFactCursorEntity?

	@Query(
		"SELECT * FROM ambient_wifi_fact_revision WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId " +
			"AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun revision(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): AmbientWifiFactRevisionEntity?

	@Query(
		"UPDATE ambient_wifi_fact_cursor SET latest_semantic_revision = :newSemanticRevision, " +
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
		"SELECT fact.* FROM ambient_wifi_fact_revision AS fact " +
			"INNER JOIN ambient_wifi_fact_cursor AS cursor ON cursor.writer_id = fact.writer_id " +
			"AND cursor.writer_version = fact.writer_version AND cursor.logical_fact_id = fact.logical_fact_id " +
			"AND cursor.latest_semantic_revision = fact.semantic_revision " +
			"WHERE fact.fact_kind = '${AmbientWifiFactRevisionEntity.FACT_KIND_AGGREGATE}' " +
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
	): AmbientWifiFactRevisionEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertGap(value: AmbientWifiGapEntity): Long

	@Query(
		"SELECT fact.*, owner.observation_count AS owner_observation_count, " +
			"owner.two_point_four_ghz_count AS owner_two_point_four_ghz_count, " +
			"owner.five_ghz_count AS owner_five_ghz_count, " +
			"owner.six_ghz_count AS owner_six_ghz_count, owner.other_band_count AS owner_other_band_count, " +
			"owner.strongest_signal_dbm AS owner_strongest_signal_dbm, " +
			"owner.weakest_signal_dbm AS owner_weakest_signal_dbm, " +
			"owner.signal_sum_dbm AS owner_signal_sum_dbm " +
			"FROM ambient_wifi_fact_revision AS fact " +
			"INNER JOIN ambient_wifi_fact_cursor AS cursor ON cursor.writer_id = fact.writer_id " +
			"AND cursor.writer_version = fact.writer_version AND cursor.logical_fact_id = fact.logical_fact_id " +
			"AND cursor.latest_semantic_revision = fact.semantic_revision " +
			"LEFT JOIN ambient_wifi_fact_revision AS owner ON owner.writer_id = fact.writer_id " +
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
	): List<AmbientWifiEffectiveLocalRow>

	@Query(
		"SELECT fact.*, owner.observation_count AS owner_observation_count, " +
			"owner.two_point_four_ghz_count AS owner_two_point_four_ghz_count, " +
			"owner.five_ghz_count AS owner_five_ghz_count, " +
			"owner.six_ghz_count AS owner_six_ghz_count, owner.other_band_count AS owner_other_band_count, " +
			"owner.strongest_signal_dbm AS owner_strongest_signal_dbm, " +
			"owner.weakest_signal_dbm AS owner_weakest_signal_dbm, " +
			"owner.signal_sum_dbm AS owner_signal_sum_dbm " +
			"FROM ambient_wifi_fact_revision AS fact " +
			"INNER JOIN ambient_wifi_fact_cursor AS cursor ON cursor.writer_id = fact.writer_id " +
			"AND cursor.writer_version = fact.writer_version AND cursor.logical_fact_id = fact.logical_fact_id " +
			"AND cursor.latest_semantic_revision = fact.semantic_revision " +
			"LEFT JOIN ambient_wifi_fact_revision AS owner ON owner.writer_id = fact.writer_id " +
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
	): List<AmbientWifiEffectiveLocalRow>

	@Query(
		"SELECT * FROM ambient_wifi_gap WHERE gap_end_time_ms > :fromInclusiveMs " +
			"AND gap_start_time_ms < :toExclusiveMs ORDER BY gap_start_time_ms, gap_id LIMIT :limit",
	)
	suspend fun gapsOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<AmbientWifiGapEntity>

	@Query(
		"SELECT * FROM ambient_wifi_gap WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId ORDER BY gap_start_time_ms, gap_id LIMIT :limit",
	)
	suspend fun gapsForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<AmbientWifiGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_fact AS fact WHERE observed_time_ms >= :fromInclusiveMs " +
			"AND observed_time_ms < :toExclusiveMs AND semantic_revision = " +
			"(SELECT MAX(state.semantic_revision) FROM imported_ambient_wifi_fact AS state " +
			"WHERE state.fact_id = fact.fact_id) ORDER BY observed_time_ms, archive_id, fact_id LIMIT :limit",
	)
	suspend fun importedOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientWifiFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_fact AS fact WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId AND semantic_revision = " +
			"(SELECT MAX(state.semantic_revision) FROM imported_ambient_wifi_fact AS state " +
			"WHERE state.fact_id = fact.fact_id) ORDER BY observed_time_ms, archive_id, fact_id LIMIT :limit",
	)
	suspend fun importedForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<ImportedAmbientWifiFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_gap WHERE end_time_ms > :fromInclusiveMs " +
			"AND start_time_ms < :toExclusiveMs ORDER BY start_time_ms, archive_id, gap_id LIMIT :limit",
	)
	suspend fun importedGapsOverWindow(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
	): List<ImportedAmbientWifiGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_gap WHERE structural_epoch_day = :structuralEpochDay " +
			"AND stored_zone_id = :storedZoneId ORDER BY start_time_ms, archive_id, gap_id LIMIT :limit",
	)
	suspend fun importedGapsForDay(
		structuralEpochDay: Long,
		storedZoneId: String,
		limit: Int,
	): List<ImportedAmbientWifiGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_fact WHERE archive_id = :archiveId " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedForArchive(
		archiveId: String,
		limit: Int,
	): List<ImportedAmbientWifiFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_fact WHERE fact_id IN (:factIds) " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedFactRevisions(
		factIds: List<String>,
		limit: Int,
	): List<ImportedAmbientWifiFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_gap WHERE archive_id = :archiveId " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapsForArchive(
		archiveId: String,
		limit: Int,
	): List<ImportedAmbientWifiGapEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_gap WHERE gap_id IN (:gapIds) " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapsByIds(
		gapIds: List<String>,
		limit: Int,
	): List<ImportedAmbientWifiGapEntity>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportedFact(value: ImportedAmbientWifiFactEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportedGap(value: ImportedAmbientWifiGapEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportReceipt(value: ImportedAmbientWifiReceiptEntity)

	@Query(
		"SELECT * FROM imported_ambient_wifi_receipt WHERE import_job_id = :jobId " +
			"AND import_entry_key = :entryKey LIMIT 1",
	)
	suspend fun importReceipt(jobId: String, entryKey: String): ImportedAmbientWifiReceiptEntity?

	@Query(
		"SELECT * FROM imported_ambient_wifi_tombstone WHERE archive_id = :archiveId LIMIT 1",
	)
	suspend fun importTombstone(archiveId: String): ImportedAmbientWifiTombstoneEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportTombstone(value: ImportedAmbientWifiTombstoneEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertImportTombstones(values: List<ImportedAmbientWifiTombstoneEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertReplayFootprints(values: List<AmbientWifiReplayFootprintEntity>): List<Long>

	@Query(
		"SELECT * FROM ambient_wifi_replay_footprint WHERE footprint_kind = :kind " +
			"AND identity_digest = :identityDigest AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun replayFootprint(
		kind: String,
		identityDigest: String,
		semanticRevision: Long,
	): AmbientWifiReplayFootprintEntity?

	@Query(
		"SELECT * FROM ambient_wifi_replay_footprint " +
			"WHERE identity_digest IN (:identityDigests) " +
			"ORDER BY footprint_kind, identity_digest, semantic_revision LIMIT :limit",
	)
	suspend fun replayFootprintsByIdentityDigests(
		identityDigests: List<String>,
		limit: Int,
	): List<AmbientWifiReplayFootprintEntity>

	@Query(
		"SELECT * FROM ambient_wifi_deletion_marker WHERE collected_data_epoch = :collectedDataEpoch " +
			"ORDER BY deletion_generation DESC LIMIT 1",
	)
	suspend fun latestDeletionMarker(
		collectedDataEpoch: Long,
	): AmbientWifiDeletionMarkerEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionMarker(value: AmbientWifiDeletionMarkerEntity)

	@Query(
		"WITH RECURSIVE selected(logical_fact_id) AS (" +
			"SELECT latest.logical_fact_id FROM ambient_wifi_fact_revision AS latest " +
			"WHERE latest.semantic_revision = (" +
			"SELECT MAX(history.semantic_revision) FROM ambient_wifi_fact_revision AS history " +
			"WHERE history.logical_fact_id = latest.logical_fact_id) " +
			"AND latest.observed_wall_time_ms < :beforeMs " +
			"AND latest.retention_policy_id = :retentionPolicyId " +
			"UNION SELECT aggregate_owner_logical_fact_id FROM ambient_wifi_fact_revision AS child " +
			"JOIN selected ON selected.logical_fact_id = child.logical_fact_id " +
			"WHERE child.aggregate_owner_logical_fact_id IS NOT NULL " +
			"UNION SELECT child.logical_fact_id FROM ambient_wifi_fact_revision AS child " +
			"JOIN selected ON child.aggregate_owner_logical_fact_id = selected.logical_fact_id) " +
			"SELECT logical_fact_id FROM selected ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun localFactIdsBefore(
		beforeMs: Long,
		retentionPolicyId: String,
		limit: Int,
	): List<String>

	@Query(
		"SELECT * FROM ambient_wifi_fact_revision WHERE logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun localFactLineages(
		logicalFactIds: List<String>,
		limit: Int,
	): List<AmbientWifiFactRevisionEntity>

	@Query(
		"SELECT fact.*, owner.observation_count AS owner_observation_count, " +
			"owner.two_point_four_ghz_count AS owner_two_point_four_ghz_count, " +
			"owner.five_ghz_count AS owner_five_ghz_count, " +
			"owner.six_ghz_count AS owner_six_ghz_count, " +
			"owner.other_band_count AS owner_other_band_count, " +
			"owner.strongest_signal_dbm AS owner_strongest_signal_dbm, " +
			"owner.weakest_signal_dbm AS owner_weakest_signal_dbm, " +
			"owner.signal_sum_dbm AS owner_signal_sum_dbm " +
			"FROM ambient_wifi_fact_revision AS fact " +
			"LEFT JOIN ambient_wifi_fact_revision AS owner ON owner.writer_id = fact.writer_id " +
			"AND owner.writer_version = fact.writer_version " +
			"AND owner.logical_fact_id = fact.aggregate_owner_logical_fact_id " +
			"AND owner.semantic_revision = fact.aggregate_owner_semantic_revision " +
			"WHERE fact.logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY fact.logical_fact_id, fact.semantic_revision LIMIT :limit",
	)
	suspend fun effectiveLocalLineages(
		logicalFactIds: List<String>,
		limit: Int,
	): List<AmbientWifiEffectiveLocalRow>

	@Query(
		"SELECT * FROM ambient_wifi_fact_revision ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun allLocalFactRevisions(limit: Int): List<AmbientWifiFactRevisionEntity>

	@Query("SELECT * FROM ambient_wifi_gap ORDER BY gap_id LIMIT :limit")
	suspend fun allLocalGaps(limit: Int): List<AmbientWifiGapEntity>

	@Query("DELETE FROM ambient_wifi_fact_cursor WHERE logical_fact_id IN (:logicalFactIds)")
	suspend fun deleteLocalCursors(logicalFactIds: List<String>): Int

	@Query("DELETE FROM ambient_wifi_fact_revision WHERE logical_fact_id IN (:logicalFactIds)")
	suspend fun deleteLocalFacts(logicalFactIds: List<String>): Int

	@Query("DELETE FROM ambient_wifi_gap WHERE gap_end_time_ms < :beforeMs")
	suspend fun deleteGapsBefore(beforeMs: Long): Int

	@Query(
		"SELECT * FROM ambient_wifi_gap WHERE gap_end_time_ms < :beforeMs " +
			"AND retention_policy_id = :retentionPolicyId ORDER BY gap_id LIMIT :limit",
	)
	suspend fun localGapsBefore(
		beforeMs: Long,
		retentionPolicyId: String,
		limit: Int,
	): List<AmbientWifiGapEntity>

	@Query("DELETE FROM ambient_wifi_gap WHERE gap_id IN (:gapIds)")
	suspend fun deleteLocalGapsByIdentity(gapIds: List<String>): Int

	@Query(
		"SELECT latest.fact_id FROM imported_ambient_wifi_fact AS latest " +
			"WHERE latest.semantic_revision = (" +
			"SELECT MAX(history.semantic_revision) FROM imported_ambient_wifi_fact AS history " +
			"WHERE history.fact_id = latest.fact_id) " +
			"AND latest.observed_time_ms < :beforeMs " +
			"AND latest.retention_policy_id = :retentionPolicyId " +
			"ORDER BY latest.fact_id LIMIT :limit",
	)
	suspend fun importedFactIdsBefore(
		beforeMs: Long,
		retentionPolicyId: String,
		limit: Int,
	): List<String>

	@Query(
		"SELECT DISTINCT gap_id FROM imported_ambient_wifi_gap WHERE end_time_ms < :beforeMs " +
			"AND retention_policy_id = :retentionPolicyId ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapIdsBefore(
		beforeMs: Long,
		retentionPolicyId: String,
		limit: Int,
	): List<String>

	@Query(
		"SELECT * FROM imported_ambient_wifi_fact WHERE fact_id IN (:factIds) " +
			"ORDER BY fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun importedFactLineages(
		factIds: List<String>,
		limit: Int,
	): List<ImportedAmbientWifiFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_gap WHERE gap_id IN (:gapIds) " +
			"ORDER BY gap_id LIMIT :limit",
	)
	suspend fun importedGapRows(
		gapIds: List<String>,
		limit: Int,
	): List<ImportedAmbientWifiGapEntity>

	@Query("DELETE FROM imported_ambient_wifi_fact WHERE fact_id IN (:factIds)")
	suspend fun deleteImportedFactsByIdentity(factIds: List<String>): Int

	@Query("DELETE FROM imported_ambient_wifi_gap WHERE gap_id IN (:gapIds)")
	suspend fun deleteImportedGapsByIdentity(gapIds: List<String>): Int

	@Query(
		"SELECT DISTINCT receipt.archive_id FROM imported_ambient_wifi_receipt AS receipt " +
			"WHERE NOT EXISTS (SELECT 1 FROM imported_ambient_wifi_fact AS fact " +
			"WHERE fact.archive_id = receipt.archive_id) " +
			"AND NOT EXISTS (SELECT 1 FROM imported_ambient_wifi_gap AS gap " +
			"WHERE gap.archive_id = receipt.archive_id) ORDER BY receipt.archive_id LIMIT :limit",
	)
	suspend fun emptyImportedArchiveIds(limit: Int): List<String>

	@Query(
		"SELECT * FROM imported_ambient_wifi_fact ORDER BY archive_id, fact_id, semantic_revision " +
			"LIMIT :limit",
	)
	suspend fun allImportedFacts(limit: Int): List<ImportedAmbientWifiFactEntity>

	@Query(
		"SELECT * FROM imported_ambient_wifi_gap ORDER BY archive_id, gap_id LIMIT :limit",
	)
	suspend fun allImportedGaps(limit: Int): List<ImportedAmbientWifiGapEntity>

	@Query("DELETE FROM imported_ambient_wifi_fact WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportedFacts(archiveIds: List<String>): Int

	@Query("DELETE FROM imported_ambient_wifi_gap WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportedGaps(archiveIds: List<String>): Int

	@Query("DELETE FROM imported_ambient_wifi_receipt WHERE archive_id IN (:archiveIds)")
	suspend fun deleteImportReceipts(archiveIds: List<String>): Int

	@Query(
		"SELECT archive_id FROM (SELECT archive_id FROM imported_ambient_wifi_fact UNION " +
			"SELECT archive_id FROM imported_ambient_wifi_gap) ORDER BY archive_id LIMIT :limit",
	)
	suspend fun allImportedArchiveIds(limit: Int): List<String>

	@Query("SELECT DISTINCT archive_id FROM imported_ambient_wifi_receipt ORDER BY archive_id LIMIT :limit")
	suspend fun allImportReceiptArchiveIds(limit: Int): List<String>

	@Query("DELETE FROM ambient_wifi_fact_cursor")
	suspend fun deleteAllLocalCursors(): Int

	@Query("DELETE FROM ambient_wifi_fact_revision")
	suspend fun deleteAllLocalFacts(): Int

	@Query("DELETE FROM ambient_wifi_gap")
	suspend fun deleteAllGaps(): Int

	@Query("SELECT COUNT(*) FROM ambient_wifi_fact_revision")
	suspend fun localFactCount(): Long

	@Query("SELECT COUNT(*) FROM ambient_wifi_fact_cursor")
	suspend fun localCursorCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_wifi_fact")
	suspend fun importedFactCount(): Long

	@Query("SELECT COUNT(*) FROM imported_ambient_wifi_gap")
	suspend fun importedGapCount(): Long

	@Query("DELETE FROM ambient_wifi_authority")
	suspend fun deleteAllAuthorities(): Int

	@Query("DELETE FROM ambient_wifi_retention_authority")
	suspend fun deleteAllRetentionAuthorities(): Int
}
