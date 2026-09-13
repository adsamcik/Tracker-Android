package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity

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

	@Query("SELECT COUNT(*) FROM cell_captured_fact_revision")
	suspend fun revisionCount(): Long

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
			"AND :newSourceAdmissionOrdinal > :expectedSourceAdmissionOrdinal " +
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

	@Query("DELETE FROM cell_captured_fact_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM cell_captured_fact_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM cell_capture_deletion_generation")
	fun deleteAllDeletionGenerations()
}
