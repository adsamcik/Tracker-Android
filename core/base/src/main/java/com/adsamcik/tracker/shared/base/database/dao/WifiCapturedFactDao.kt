package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.WifiCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedFactRevisionEntity

/** Bounded append-only storage boundary for dormant Wi-Fi captured facts. */
@Dao
interface WifiCapturedFactDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(entity: WifiCapturedFactRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(entity: WifiCapturedFactCursorEntity): Long

	@Query(
		"SELECT * FROM wifi_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId " +
			"AND semantic_revision = :semanticRevision LIMIT 1",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): WifiCapturedFactRevisionEntity?

	@Query(
		"SELECT * FROM wifi_captured_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT :limit",
	)
	suspend fun revisionsForFact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		limit: Int,
	): List<WifiCapturedFactRevisionEntity>

	@Query(
		"SELECT * FROM wifi_captured_fact_cursor WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId LIMIT 1",
	)
	suspend fun cursor(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): WifiCapturedFactCursorEntity?

	/** Latest effective predecessor, not the latest appended historical revision. */
	@Query(
		"SELECT fact.* FROM wifi_captured_fact_cursor AS cursor " +
			"INNER JOIN wifi_captured_fact_revision AS fact ON fact.writer_projection_id = cursor.writer_projection_id " +
			"AND fact.writer_projection_version = cursor.writer_projection_version " +
			"AND fact.logical_fact_id = cursor.logical_fact_id " +
			"AND fact.semantic_revision = cursor.latest_semantic_revision " +
			"WHERE fact.writer_projection_id = :writerProjectionId " +
			"AND fact.writer_projection_version = :writerProjectionVersion " +
			"AND fact.logical_tracking_id = :logicalTrackingId AND fact.service_run_id = :serviceRunId " +
			"AND fact.session_segment_id = :sessionSegmentId " +
			"AND fact.collected_data_epoch = :collectedDataEpoch " +
			"AND fact.scope_deletion_generation = :scopeDeletionGeneration " +
			"AND fact.source_admission_ordinal < :beforeSourceAdmissionOrdinal " +
			"ORDER BY fact.source_admission_ordinal DESC LIMIT 1",
	)
	suspend fun latestEffectiveBefore(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		sessionSegmentId: Long,
		collectedDataEpoch: Long,
		scopeDeletionGeneration: Long,
		beforeSourceAdmissionOrdinal: Long,
	): WifiCapturedFactRevisionEntity?

	@Query(
		"UPDATE wifi_captured_fact_cursor SET latest_semantic_revision = :newSemanticRevision, " +
			"latest_mutation_id = :newMutationId, latest_effect_checksum = :newEffectChecksum, " +
			"latest_source_admission_ordinal = :newSourceAdmissionOrdinal, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND logical_fact_id = :logicalFactId " +
			"AND logical_tracking_id = :logicalTrackingId AND service_run_id = :serviceRunId " +
			"AND session_segment_id = :sessionSegmentId AND writer_owner_generation = :writerOwnerGeneration " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND scope_deletion_generation = :scopeDeletionGeneration " +
			"AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND latest_source_admission_ordinal = :expectedSourceAdmissionOrdinal " +
			"AND cursor_revision = :expectedCursorRevision",
	)
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
		"SELECT COUNT(*) FROM wifi_captured_fact_revision WHERE aggregate_owner_logical_fact_id = :logicalFactId " +
			"AND aggregate_owner_semantic_revision = :semanticRevision",
	)
	suspend fun dependentCount(logicalFactId: String, semanticRevision: Long): Long

	@Query(
		"SELECT * FROM wifi_capture_deletion_generation WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId LIMIT 1",
	)
	suspend fun deletionGeneration(
		logicalTrackingId: String,
		serviceRunId: String,
	): WifiCaptureDeletionGenerationEntity?

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeletionGeneration(entity: WifiCaptureDeletionGenerationEntity)

	@Query(
		"UPDATE wifi_capture_deletion_generation SET generation = :newGeneration, " +
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

	@Query("SELECT COUNT(*) FROM wifi_captured_fact_revision")
	suspend fun revisionCount(): Long

	@Query("SELECT COUNT(*) FROM wifi_captured_fact_cursor")
	suspend fun cursorCount(): Long

	@Query("SELECT COUNT(*) FROM wifi_capture_deletion_generation")
	suspend fun deletionGenerationCount(): Long

	@Query("DELETE FROM wifi_captured_fact_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM wifi_captured_fact_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM wifi_capture_deletion_generation")
	fun deleteAllDeletionGenerations()
}
