package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity

data class CellWalMaintenanceKey(
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "event_id") val eventId: String,
)

/** Narrow source-local persistence boundary for dormant captured Cell facts. */
@Dao
interface CellCapturedFactDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(entity: CellCapturedFactRevisionEntity): Long

	@Query(
		"SELECT * FROM cell_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): CellCapturedFactRevisionEntity?

	@Query(
		"SELECT * FROM cell_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT :limit",
	)
	suspend fun revisionsForFact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		limit: Int,
	): List<CellCapturedFactRevisionEntity>

	@Query("SELECT COUNT(*) FROM cell_captured_fact_revision")
	suspend fun revisionCount(): Long

	/** Stable bounded keyset over every retained Cell fact revision. */
	@Query(
		"SELECT * FROM cell_captured_fact_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND (" +
			":afterLogicalFactId IS NULL OR logical_fact_id > :afterLogicalFactId OR " +
			"(logical_fact_id = :afterLogicalFactId AND semantic_revision > :afterSemanticRevision)) " +
			"ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun maintenanceRevisionPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<CellCapturedFactRevisionEntity>

	/** Complete bounded fact side of one selected portable logical/run closure. */
	@Query(
		"SELECT * FROM cell_captured_fact_revision WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY logical_fact_id, semantic_revision LIMIT :limit",
	)
	suspend fun portableRevisionClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellCapturedFactRevisionEntity>

	/** Stable bounded keyset used to prove one current head per retained Cell lineage. */
	@Query(
		"SELECT * FROM cell_captured_fact_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND (:afterLogicalFactId IS NULL OR logical_fact_id > :afterLogicalFactId) " +
			"ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun maintenanceCursorPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalFactId: String?,
		limit: Int,
	): List<CellCapturedFactCursorEntity>

	/** Complete bounded cursor side of one selected portable logical/run closure. */
	@Query(
		"SELECT * FROM cell_captured_fact_cursor WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY logical_fact_id LIMIT :limit",
	)
	suspend fun portableCursorClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellCapturedFactCursorEntity>

	/** Stable bounded keyset over source-local no-resurrection generations. */
	@Query(
		"SELECT * FROM cell_capture_deletion_generation WHERE " +
			":afterLogicalTrackingId IS NULL OR logical_tracking_id > :afterLogicalTrackingId OR " +
			"(logical_tracking_id = :afterLogicalTrackingId AND service_run_id > :afterServiceRunId) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	suspend fun maintenanceDeletionGenerationPage(
		afterLogicalTrackingId: String?,
		afterServiceRunId: String?,
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

	/** Complete bounded deletion-generation side of one selected portable logical/run closure. */
	@Query(
		"SELECT * FROM cell_capture_deletion_generation WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY logical_tracking_id, service_run_id LIMIT :limit",
	)
	suspend fun portableDeletionGenerationClosure(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

	@Query(
		"SELECT COUNT(*) FROM cell_captured_fact_revision WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedRevisionCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM cell_captured_fact_cursor WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedCursorCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	/** Only direct session capture is retired by captured-Cell consent deletion. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND purpose = 'SESSION_CAPTURE' AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun directCellCaptureDemandsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<SourceDemandEntity>

	/** Exact bounded demand vector from which the current Cell authorization is derived. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind AND status = 'ACTIVE' " +
			"ORDER BY purpose, consumer_id, demand_id LIMIT :limit",
	)
	suspend fun activeCellDemandsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"UPDATE source_demand SET status = 'RETIRED', " +
			"retire_boot_id = COALESCE(retire_boot_id, :bootId), " +
			"retire_elapsed_realtime_nanos = COALESCE(retire_elapsed_realtime_nanos, :elapsedRealtimeNanos), " +
			"retired_at_ms = COALESCE(retired_at_ms, :wallTimeMs) " +
			"WHERE source_kind = :sourceKind AND purpose = 'SESSION_CAPTURE' " +
			"AND status IN ('RETIRING', 'BLOCKED')",
	)
	suspend fun retireCellCaptureDemandsForDeletion(
		sourceKind: Int,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/** Every nonterminal Cell registration represents active or pending callback work. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING') " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun cellRegistrationsForDeletion(
		sourceKind: Int,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = :authorizationRevision ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceAuthorizationMembers(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration",
	)
	suspend fun maximumRegistrationAuthorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration AND effective_boot_id = :bootId " +
			"AND authorization_revision = (SELECT authorization_revision FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authorization_revision DESC LIMIT 1) " +
			"ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceAuthorizationAt(
		sourceKind: Int,
		registrationGeneration: Long,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = (SELECT MIN(authorization_revision) " +
			"FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision > :authorizationRevision) ORDER BY member_id LIMIT :limit",
	)
	suspend fun maintenanceNextAuthorizationMembers(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT * FROM source_desired_plan WHERE revision = :revision " +
			"ORDER BY source_kind LIMIT :limit",
	)
	suspend fun maintenanceDesiredPlans(
		revision: Long,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	@Query(
		"SELECT fact.* FROM cell_captured_fact_revision AS fact " +
			"INNER JOIN cell_captured_fact_cursor AS fact_cursor ON " +
			"fact_cursor.writer_projection_id = fact.writer_projection_id AND " +
			"fact_cursor.writer_projection_version = fact.writer_projection_version AND " +
			"fact_cursor.logical_fact_id = fact.logical_fact_id AND " +
			"fact_cursor.latest_semantic_revision = fact.semantic_revision AND " +
			"fact_cursor.latest_mutation_id = fact.mutation_id AND " +
			"fact_cursor.latest_effect_checksum = fact.effect_checksum AND " +
			"fact_cursor.latest_source_admission_ordinal = fact.source_admission_ordinal " +
			"WHERE fact.writer_projection_id = :writerProjectionId AND " +
			"fact.writer_projection_version = :writerProjectionVersion AND " +
			"fact.logical_tracking_id = :logicalTrackingId AND " +
			"fact.service_run_id = :serviceRunId AND " +
			"fact.session_segment_id = :sessionSegmentId AND " +
			"fact.collected_data_epoch = :collectedDataEpoch AND " +
			"fact.scope_deletion_generation = :scopeDeletionGeneration AND " +
			"fact.source_admission_ordinal < :beforeSourceAdmissionOrdinal " +
			"ORDER BY fact.source_admission_ordinal DESC LIMIT 1",
	)
	@Suppress("LongParameterList")
	suspend fun latestEffectiveBefore(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		beforeSourceAdmissionOrdinal: Long,
	): CellCapturedFactRevisionEntity?

	@Query(
		"SELECT * FROM cell_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): CellCapturedFactCursorEntity?

	@Query("SELECT COUNT(*) FROM cell_captured_fact_cursor")
	suspend fun cursorCount(): Long

	@Query("SELECT COUNT(*) FROM source_event_wal WHERE source_kind = :sourceKind")
	suspend fun maintenanceWalCount(sourceKind: Int): Long

	@Query(
		"SELECT admission_ordinal, event_id FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND admission_ordinal > :afterAdmissionOrdinal " +
			"ORDER BY admission_ordinal LIMIT :limit",
	)
	suspend fun maintenanceWalKeys(
		sourceKind: Int,
		afterAdmissionOrdinal: Long,
		limit: Int,
	): List<CellWalMaintenanceKey>

	/** Complete bounded WAL side of one selected portable logical/run closure. */
	@Query(
		"SELECT admission_ordinal, event_id FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND (logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY admission_ordinal LIMIT :limit",
	)
	suspend fun portableWalClosureKeys(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellWalMaintenanceKey>

	/** Payload size guard used before maintenance loads and hashes one exact retained Cell WAL. */
	@Query("SELECT length(payload) FROM source_event_wal WHERE event_id = :eventId LIMIT 1")
	suspend fun maintenanceWalPayloadByteCount(eventId: String): Long?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(entity: CellCapturedFactCursorEntity): Long

	@Query(
		"UPDATE cell_captured_fact_cursor SET " +
			"latest_semantic_revision = :newSemanticRevision, latest_mutation_id = :newMutationId, " +
			"latest_effect_checksum = :newEffectChecksum, " +
			"latest_source_admission_ordinal = :newSourceAdmissionOrdinal, " +
			"cursor_revision = :newCursorRevision, updated_at_ms = :updatedAtMs " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND session_segment_id = :sessionSegmentId " +
			"AND writer_owner_generation = :writerOwnerGeneration " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND scope_deletion_generation = :scopeDeletionGeneration " +
			"AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId " +
			"AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND latest_source_admission_ordinal = :expectedSourceAdmissionOrdinal " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND :newSemanticRevision = :expectedSemanticRevision + 1 " +
			"AND :newSourceAdmissionOrdinal >= :expectedSourceAdmissionOrdinal " +
			"AND :newCursorRevision = :expectedCursorRevision + 1",
	)
	@Suppress("LongParameterList")
	suspend fun advanceCursorExact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		writerOwnerGeneration: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		expectedSemanticRevision: Long,
		expectedMutationId: String,
		expectedEffectChecksum: String,
		expectedSourceAdmissionOrdinal: Long,
		expectedCursorRevision: Long,
		newSemanticRevision: Long,
		newMutationId: String,
		newEffectChecksum: String,
		newSourceAdmissionOrdinal: Long,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"SELECT * FROM cell_capture_deletion_generation " +
			"WHERE logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId LIMIT 1",
	)
	suspend fun deletionGeneration(
		logicalTrackingId: String,
		serviceRunId: String,
	): CellCaptureDeletionGenerationEntity?

	@Query("SELECT COUNT(*) FROM cell_capture_deletion_generation")
	suspend fun deletionGenerationCount(): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionGeneration(entity: CellCaptureDeletionGenerationEntity)

	@Query(
		"UPDATE cell_capture_deletion_generation SET generation = :newGeneration, " +
			"updated_at_ms = :updatedAtMs WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND collected_data_epoch = :collectedDataEpoch " +
			"AND generation = :expectedGeneration AND :newGeneration = :expectedGeneration + 1",
	)
	suspend fun advanceDeletionGenerationExact(
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
		expectedGeneration: Long,
		newGeneration: Long,
		updatedAtMs: Long,
	): Int

	@Query(
		"DELETE FROM cell_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactCursors(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	@Query(
		"DELETE FROM cell_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactRevisionLineages(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	@Query("DELETE FROM cell_captured_fact_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM cell_captured_fact_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM cell_capture_deletion_generation")
	fun deleteAllDeletionGenerations()
}
