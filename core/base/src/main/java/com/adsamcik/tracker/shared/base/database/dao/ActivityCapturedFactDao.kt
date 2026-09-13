package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedEvidenceEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity

/** Narrow append-only storage boundary for the dormant captured Activity writer. */
@Dao
interface ActivityCapturedFactDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRegistrationPlanBinding(entity: ActivityCapturedRegistrationPlanEntity): Long

	@Query(
		"SELECT * FROM activity_captured_registration_plan " +
			"WHERE source_instance_id = :sourceInstanceId " +
			"AND registration_generation = :registrationGeneration LIMIT 1",
	)
	suspend fun registrationPlanBinding(
		sourceInstanceId: String,
		registrationGeneration: Long,
	): ActivityCapturedRegistrationPlanEntity?

	/** Exact next immutable authorization boundary in the provider's canonical elapsed-time order. */
	@Query(
		"SELECT effective_elapsed_realtime_nanos FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND effective_boot_id = :bootId " +
			"AND (effective_elapsed_realtime_nanos > :effectiveElapsedRealtimeNanos " +
			"OR (effective_elapsed_realtime_nanos = :effectiveElapsedRealtimeNanos " +
			"AND authorization_revision > :authorizationRevision)) " +
			"ORDER BY effective_elapsed_realtime_nanos, authorization_revision LIMIT 1",
	)
	suspend fun nextAuthorizationBoundary(
		sourceKind: Int,
		registrationGeneration: Long,
		bootId: String,
		effectiveElapsedRealtimeNanos: Long,
		authorizationRevision: Long,
	): Long?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRevision(entity: ActivityCapturedWindowRevisionEntity): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertFragments(entities: List<ActivityCapturedFragmentEntity>)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertEvidence(entities: List<ActivityCapturedEvidenceEntity>)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertCursor(entity: ActivityCapturedWindowCursorEntity): Long

	@Query(
		"SELECT * FROM activity_captured_window_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"LIMIT 1",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
	): ActivityCapturedWindowRevisionEntity?

	@Query(
		"SELECT * FROM activity_captured_fragment " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal",
	)
	suspend fun fragments(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
	): List<ActivityCapturedFragmentEntity>

	@Query(
		"SELECT * FROM activity_captured_evidence " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal, evidence_ordinal",
	)
	suspend fun evidence(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
	): List<ActivityCapturedEvidenceEntity>

	@Query(
		"SELECT * FROM activity_captured_window_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId LIMIT 1",
	)
	suspend fun cursor(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
	): ActivityCapturedWindowCursorEntity?

	/**
	 * Discovers one fact-backed segment per logical Activity entry without consulting sample_count.
	 * The reader expands and authenticates every replacement member before returning product data.
	 */
	@Query(
		"""
		WITH fact_backed_member AS (
		  SELECT segment.*
		  FROM session_segment AS segment
		  INNER JOIN source_service_run AS run
		    ON run.session_segment_id = segment.id
		   AND run.service_run_id = segment.service_run_id
		   AND run.logical_tracking_id = segment.logical_tracking_id
		  WHERE EXISTS (
		    SELECT 1
		    FROM activity_captured_window_revision AS fact
		    WHERE fact.service_run_id = run.service_run_id
		      AND fact.logical_tracking_id = run.logical_tracking_id
		      AND fact.session_segment_id = segment.id
		      AND fact.purpose = 'SESSION_CAPTURE'
		  )
		), logical_seed AS (
		  SELECT member.*
		  FROM fact_backed_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1 FROM fact_backed_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (
		        newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id)
		      )
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
		   OR (
		     logical_recency_start_ms = :beforeStartTimeMs
		     AND logical_recency_segment_id < COALESCE(:beforeSegmentId, 9223372036854775807)
		   )
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun logicalHistoryCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<ActivityLogicalHistoryCandidate>

	/** Pages a correction-expanded fact universe for exact physical/logical candidates. */
	@Query(
		"""
		SELECT * FROM activity_captured_window_revision AS fact
		WHERE (
		     fact.service_run_id IN (:serviceRunIds)
		   OR fact.logical_tracking_id IN (:logicalTrackingIds)
		   OR EXISTS (
		     SELECT 1 FROM activity_captured_window_revision AS candidate
		     WHERE candidate.writer_projection_id = fact.writer_projection_id
		       AND candidate.writer_projection_version = fact.writer_projection_version
		       AND candidate.logical_window_id = fact.logical_window_id
		       AND (
		         candidate.service_run_id IN (:serviceRunIds)
		         OR candidate.logical_tracking_id IN (:logicalTrackingIds)
		       )
		   )
		)
		AND (
		  :afterWriterProjectionId IS NULL
		  OR writer_projection_id > :afterWriterProjectionId
		  OR (writer_projection_id = :afterWriterProjectionId AND (
		    writer_projection_version > COALESCE(:afterWriterProjectionVersion, -1)
		    OR (writer_projection_version = COALESCE(:afterWriterProjectionVersion, -1) AND (
		      logical_window_id > COALESCE(:afterLogicalWindowId, '')
		      OR (logical_window_id = COALESCE(:afterLogicalWindowId, '') AND
		          semantic_revision > COALESCE(:afterSemanticRevision, -1))
		    ))
		  ))
		)
		ORDER BY writer_projection_id, writer_projection_version, logical_window_id, semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun historyRevisionPage(
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
		limit: Int,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
		afterLogicalWindowId: String?,
		afterSemanticRevision: Long?,
	): List<ActivityCapturedWindowRevisionEntity>

	@Query(
		"SELECT * FROM activity_captured_window_cursor " +
			"WHERE logical_window_id IN (:logicalWindowIds) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_window_id LIMIT :limit",
	)
	suspend fun historyCursors(
		logicalWindowIds: List<String>,
		limit: Int,
	): List<ActivityCapturedWindowCursorEntity>

	@Query(
		"SELECT * FROM activity_captured_fragment " +
			"WHERE logical_window_id IN (:logicalWindowIds) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_window_id, " +
			"semantic_revision, fragment_ordinal LIMIT :limit",
	)
	suspend fun historyFragments(
		logicalWindowIds: List<String>,
		limit: Int,
	): List<ActivityCapturedFragmentEntity>

	@Query(
		"SELECT * FROM activity_captured_evidence " +
			"WHERE logical_window_id IN (:logicalWindowIds) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_window_id, " +
			"semantic_revision, fragment_ordinal, evidence_ordinal LIMIT :limit",
	)
	suspend fun historyEvidence(
		logicalWindowIds: List<String>,
		limit: Int,
	): List<ActivityCapturedEvidenceEntity>

	@Query(
		"SELECT * FROM activity_captured_registration_plan " +
			"WHERE source_instance_id IN (:sourceInstanceIds) " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY source_instance_id, registration_generation LIMIT :limit",
	)
	suspend fun historyRegistrationPlans(
		sourceInstanceIds: List<String>,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ActivityCapturedRegistrationPlanEntity>

	/** Immutable acquisition-plan headers referenced by the exact manifest snapshot. */
	@Query(
		"SELECT * FROM acquisition_plan_revision WHERE revision IN (:revisions) " +
			"ORDER BY revision LIMIT :limit",
	)
	suspend fun historyAcquisitionPlanRevisions(
		revisions: List<Long>,
		limit: Int,
	): List<AcquisitionPlanRevisionEntity>

	/** Exact immutable Activity desired-plan members referenced by captured manifests. */
	@Query(
		"SELECT * FROM source_desired_plan WHERE source_kind = :sourceKind " +
			"AND revision IN (:revisions) ORDER BY revision LIMIT :limit",
	)
	suspend fun historyDesiredPlans(
		sourceKind: Int,
		revisions: List<Long>,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	/** Immutable provider generations referenced by the bounded captured-fact snapshot. */
	@Query(
		"SELECT * FROM provider_registration_generation " +
			"WHERE source_kind = :sourceKind AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun historyProviderRegistrations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	/** Complete authorization timelines for referenced provider generations, including revocation. */
	@Query(
		"SELECT * FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation, effective_elapsed_realtime_nanos, " +
			"authorization_revision, member_id LIMIT :limit",
	)
	suspend fun historyAuthorizations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<SourceAuthorizationEntity>

	/** Earliest possible band wall time across both uncertain boundaries and the full lineage. */
	@Query(
		"SELECT MIN(CASE " +
			"WHEN start_wall_time_ms <= start_wall_time_uncertainty_ms " +
				"OR end_wall_time_ms <= end_wall_time_uncertainty_ms THEN 0 " +
			"WHEN start_wall_time_ms - start_wall_time_uncertainty_ms <= " +
				"end_wall_time_ms - end_wall_time_uncertainty_ms " +
				"THEN start_wall_time_ms - start_wall_time_uncertainty_ms " +
			"ELSE end_wall_time_ms - end_wall_time_uncertainty_ms END) " +
			"FROM activity_captured_fragment " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND fragment_kind = 'BAND'",
	)
	suspend fun earliestPossibleBandWallTimeMs(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
	): Long?

	@Query(
		"UPDATE activity_captured_window_cursor SET " +
			"latest_semantic_revision = :newSemanticRevision, " +
			"latest_mutation_id = :newMutationId, " +
			"latest_effect_checksum = :newEffectChecksum, cursor_revision = :newCursorRevision, " +
			"updated_at_ms = :updatedAtMs " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId " +
			"AND writer_owner_generation = :writerOwnerGeneration " +
			"AND latest_semantic_revision = :expectedSemanticRevision " +
			"AND latest_mutation_id = :expectedMutationId " +
			"AND latest_effect_checksum = :expectedEffectChecksum " +
			"AND cursor_revision = :expectedCursorRevision " +
			"AND collected_data_epoch = :collectedDataEpoch " +
			"AND :newSemanticRevision = latest_semantic_revision + 1 " +
			"AND :newCursorRevision = cursor_revision + 1",
	)
	@Suppress("LongParameterList")
	suspend fun advanceCursorExact(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		writerOwnerGeneration: Long,
		collectedDataEpoch: Long,
		expectedSemanticRevision: Long,
		expectedMutationId: String,
		expectedEffectChecksum: String,
		expectedCursorRevision: Long,
		newSemanticRevision: Long,
		newMutationId: String,
		newEffectChecksum: String,
		newCursorRevision: Long,
		updatedAtMs: Long,
	): Int

	@Query("SELECT COUNT(*) FROM activity_captured_window_revision")
	suspend fun revisionCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_registration_plan")
	suspend fun registrationPlanBindingCount(): Long

	@Query("DELETE FROM activity_captured_evidence")
	fun deleteAllEvidence()

	@Query("DELETE FROM activity_captured_fragment")
	fun deleteAllFragments()

	@Query("DELETE FROM activity_captured_window_cursor")
	fun deleteAllCursors()

	@Query("DELETE FROM activity_captured_window_revision")
	fun deleteAllRevisions()

	@Query("DELETE FROM activity_captured_registration_plan")
	fun deleteAllRegistrationPlanBindings()
}

/** Fact-backed seed plus the newest reciprocal member used only for stable keyset ordering. */
data class ActivityLogicalHistoryCandidate(
	@Embedded val segment: SessionSegment,
	@ColumnInfo(name = "logical_recency_start_ms") val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id") val logicalRecencySegmentId: Long,
)
