package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactCursorEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity

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

	/**
	 * Discovers one fact-backed segment per logical Cell entry. Presentation sample_count,
	 * legacy radio rows, and Location are deliberately absent from this authority.
	 */
	@Query(
		"""
		WITH current_fact_member AS (
		  SELECT segment.*
		  FROM session_segment AS segment
		  INNER JOIN source_service_run AS run
		    ON run.session_segment_id = segment.id
		   AND run.service_run_id = segment.service_run_id
		   AND run.logical_tracking_id = segment.logical_tracking_id
		  WHERE EXISTS (
		    SELECT 1
		    FROM cell_captured_fact_revision AS fact
		    WHERE fact.logical_tracking_id = run.logical_tracking_id
		      AND fact.service_run_id = run.service_run_id
		      AND fact.session_segment_id = segment.id
		      AND fact.purpose = 'SESSION_CAPTURE'
		  )
		), logical_seed AS (
		  SELECT member.*
		  FROM current_fact_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1 FROM current_fact_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id))
		  )
		), ranked_seed AS (
		  SELECT seed.*,
		    (SELECT MAX(member_segment.start_time_ms)
		     FROM source_service_run AS member_run
		     INNER JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		    ) AS logical_recency_start_ms,
		    (SELECT MAX(member_segment.id)
		     FROM source_service_run AS member_run
		     INNER JOIN session_segment AS member_segment
		       ON member_segment.id = member_run.session_segment_id
		      AND member_segment.service_run_id = member_run.service_run_id
		      AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		     WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		       AND member_segment.start_time_ms = (
		         SELECT MAX(latest_segment.start_time_ms)
		         FROM source_service_run AS latest_run
		         INNER JOIN session_segment AS latest_segment
		           ON latest_segment.id = latest_run.session_segment_id
		          AND latest_segment.service_run_id = latest_run.service_run_id
		          AND latest_segment.logical_tracking_id = latest_run.logical_tracking_id
		         WHERE latest_run.logical_tracking_id = seed.logical_tracking_id
		       )
		    ) AS logical_recency_segment_id
		  FROM logical_seed AS seed
		)
		SELECT * FROM ranked_seed
		WHERE :beforeStartTimeMs IS NULL
		   OR logical_recency_start_ms < :beforeStartTimeMs
		   OR (logical_recency_start_ms = :beforeStartTimeMs
		       AND logical_recency_segment_id < COALESCE(:beforeSegmentId, 9223372036854775807))
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun logicalHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<CellLogicalHistoryCandidate>

	/** Pages complete correction lineages and direct aggregate owners for the selected universe. */
	@Query(
		"""
		SELECT * FROM cell_captured_fact_revision AS fact
		WHERE (
		     fact.service_run_id IN (:serviceRunIds)
		   OR fact.logical_tracking_id IN (:logicalTrackingIds)
		   OR EXISTS (
		     SELECT 1 FROM cell_captured_fact_revision AS candidate
		     WHERE candidate.writer_projection_id = fact.writer_projection_id
		       AND candidate.writer_projection_version = fact.writer_projection_version
		       AND candidate.logical_fact_id = fact.logical_fact_id
		       AND (candidate.service_run_id IN (:serviceRunIds)
		         OR candidate.logical_tracking_id IN (:logicalTrackingIds))
		   )
		   OR EXISTS (
		     SELECT 1 FROM cell_captured_fact_revision AS dependent
		     WHERE dependent.aggregate_owner_logical_fact_id = fact.logical_fact_id
		       AND (dependent.service_run_id IN (:serviceRunIds)
		         OR dependent.logical_tracking_id IN (:logicalTrackingIds))
		   )
		)
		AND (
		  :afterWriterProjectionId IS NULL
		  OR writer_projection_id > :afterWriterProjectionId
		  OR (writer_projection_id = :afterWriterProjectionId AND (
		    writer_projection_version > COALESCE(:afterWriterProjectionVersion, -1)
		    OR (writer_projection_version = COALESCE(:afterWriterProjectionVersion, -1) AND (
		      logical_fact_id > COALESCE(:afterLogicalFactId, '')
		      OR (logical_fact_id = COALESCE(:afterLogicalFactId, '') AND
		          semantic_revision > COALESCE(:afterSemanticRevision, -1))
		    ))
		  ))
		)
		ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun historyRevisionPage(
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
		limit: Int,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
	): List<CellCapturedFactRevisionEntity>

	@Query(
		"SELECT * FROM cell_captured_fact_cursor WHERE logical_fact_id IN (:logicalFactIds) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id LIMIT :limit",
	)
	suspend fun historyCursors(
		logicalFactIds: List<String>,
		limit: Int,
	): List<CellCapturedFactCursorEntity>

	@Query(
		"SELECT generation.* FROM cell_capture_deletion_generation AS generation " +
			"INNER JOIN source_service_run AS run ON " +
			"run.logical_tracking_id = generation.logical_tracking_id AND " +
			"run.service_run_id = generation.service_run_id " +
			"WHERE generation.logical_tracking_id IN (:logicalTrackingIds) " +
			"AND generation.service_run_id IN (:serviceRunIds) " +
			"ORDER BY generation.logical_tracking_id, generation.service_run_id LIMIT :limit",
	)
	suspend fun historyDeletionGenerations(
		logicalTrackingIds: List<String>,
		serviceRunIds: List<String>,
		limit: Int,
	): List<CellCaptureDeletionGenerationEntity>

	@Query("SELECT * FROM acquisition_plan_revision WHERE revision IN (:revisions) ORDER BY revision LIMIT :limit")
	suspend fun historyAcquisitionPlanRevisions(
		revisions: List<Long>,
		limit: Int,
	): List<AcquisitionPlanRevisionEntity>

	@Query(
		"SELECT * FROM source_desired_plan WHERE source_kind = :sourceKind " +
			"AND revision IN (:revisions) ORDER BY revision LIMIT :limit",
	)
	suspend fun historyDesiredPlans(
		sourceKind: Int,
		revisions: List<Long>,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun historyProviderRegistrations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation, effective_elapsed_realtime_nanos, " +
			"authorization_revision, member_id LIMIT :limit",
	)
	suspend fun historyAuthorizations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query("DELETE FROM cell_captured_fact_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM cell_captured_fact_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM cell_capture_deletion_generation")
	fun deleteAllDeletionGenerations()
}

data class CellLogicalHistoryCandidate(
	@Embedded val segment: SessionSegment,
	@ColumnInfo(name = "logical_recency_start_ms") val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id") val logicalRecencySegmentId: Long,
)
