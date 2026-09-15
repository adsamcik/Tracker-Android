package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
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
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity

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

	/** Stable bounded revision keyset used by Activity-owned maintenance. */
	@Query(
		"SELECT * FROM activity_captured_window_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion AND (" +
			":afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId OR " +
			"(logical_window_id = :afterLogicalWindowId " +
			"AND semantic_revision > :afterSemanticRevision)) " +
			"ORDER BY logical_window_id, semantic_revision LIMIT :limit",
	)
	suspend fun maintenanceRevisionPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalWindowId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<ActivityCapturedWindowRevisionEntity>

	/**
	 * Bounded selected-session audit. The union deliberately includes either side of the immutable
	 * logical/run identity so a row that disagrees with its claimed scope cannot be skipped.
	 */
	@Query(
		"SELECT * FROM activity_captured_window_revision WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)) AND (" +
			":afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId OR " +
			"(logical_window_id = :afterLogicalWindowId " +
			"AND semantic_revision > :afterSemanticRevision)) " +
			"ORDER BY logical_window_id, semantic_revision LIMIT :limit",
	)
	suspend fun selectedRevisionPage(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
		afterLogicalWindowId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<ActivityCapturedWindowRevisionEntity>

	/** Stable bounded cursor keyset used to prove one current head per retained lineage. */
	@Query(
		"SELECT * FROM activity_captured_window_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND (:afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId) " +
			"ORDER BY logical_window_id LIMIT :limit",
	)
	suspend fun maintenanceCursorPage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		afterLogicalWindowId: String?,
		limit: Int,
	): List<ActivityCapturedWindowCursorEntity>

	@Query(
		"SELECT * FROM activity_captured_window_cursor WHERE " +
			"(logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)) " +
			"AND (:afterLogicalWindowId IS NULL OR logical_window_id > :afterLogicalWindowId) " +
			"ORDER BY logical_window_id LIMIT :limit",
	)
	suspend fun selectedCursorPage(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
		afterLogicalWindowId: String?,
		limit: Int,
	): List<ActivityCapturedWindowCursorEntity>

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_revision WHERE " +
			"logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedRevisionCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_cursor WHERE " +
			"logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds) " +
			"OR session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedCursorCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_fragment AS fragment " +
			"INNER JOIN activity_captured_window_revision AS revision " +
			"ON revision.writer_projection_id = fragment.writer_projection_id " +
			"AND revision.writer_projection_version = fragment.writer_projection_version " +
			"AND revision.logical_window_id = fragment.logical_window_id " +
			"AND revision.semantic_revision = fragment.semantic_revision " +
			"WHERE revision.logical_tracking_id = :logicalTrackingId " +
			"OR revision.service_run_id IN (:serviceRunIds) " +
			"OR revision.session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedFragmentCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_evidence AS evidence " +
			"INNER JOIN activity_captured_window_revision AS revision " +
			"ON revision.writer_projection_id = evidence.writer_projection_id " +
			"AND revision.writer_projection_version = evidence.writer_projection_version " +
			"AND revision.logical_window_id = evidence.logical_window_id " +
			"AND revision.semantic_revision = evidence.semantic_revision " +
			"WHERE revision.logical_tracking_id = :logicalTrackingId " +
			"OR revision.service_run_id IN (:serviceRunIds) " +
			"OR revision.session_segment_id IN (:sessionSegmentIds)",
	)
	suspend fun selectedEvidenceCount(
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		sessionSegmentIds: List<Long>,
	): Long

	/** Includes unsupported and orphaned child rows that still claim a selected window identity. */
	@Query(
		"SELECT COUNT(*) FROM activity_captured_fragment " +
			"WHERE logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun payloadFragmentCountForWindows(logicalWindowIds: List<String>): Long

	/** Includes unsupported and orphaned evidence that still claims a selected window identity. */
	@Query(
		"SELECT COUNT(*) FROM activity_captured_evidence " +
			"WHERE logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun payloadEvidenceCountForWindows(logicalWindowIds: List<String>): Long

	/** SQLite-enforced fragment cap; callers request one overflow row. */
	@Query(
		"SELECT * FROM activity_captured_fragment " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal LIMIT :limit",
	)
	suspend fun maintenanceFragments(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
		limit: Int,
	): List<ActivityCapturedFragmentEntity>

	/** SQLite-enforced evidence cap; callers request one overflow row. */
	@Query(
		"SELECT * FROM activity_captured_evidence " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id = :logicalWindowId AND semantic_revision = :semanticRevision " +
			"ORDER BY fragment_ordinal, evidence_ordinal LIMIT :limit",
	)
	suspend fun maintenanceEvidence(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowId: String,
		semanticRevision: Long,
		limit: Int,
	): List<ActivityCapturedEvidenceEntity>

	/** Stable bounded plan-binding keyset for complete captured-source deletion audits. */
	@Query(
		"SELECT * FROM activity_captured_registration_plan WHERE " +
			":afterSourceInstanceId IS NULL OR source_instance_id > :afterSourceInstanceId OR " +
			"(source_instance_id = :afterSourceInstanceId " +
			"AND registration_generation > :afterRegistrationGeneration) " +
			"ORDER BY source_instance_id, registration_generation LIMIT :limit",
	)
	suspend fun maintenanceRegistrationPlanPage(
		afterSourceInstanceId: String?,
		afterRegistrationGeneration: Long?,
		limit: Int,
	): List<ActivityCapturedRegistrationPlanEntity>

	/** Bounded immutable applied-plan bindings used to authenticate terminal export settlement. */
	@Query(
		"SELECT * FROM activity_captured_registration_plan " +
			"WHERE registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation, source_instance_id LIMIT :limit",
	)
	suspend fun portableRegistrationPlans(
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ActivityCapturedRegistrationPlanEntity>

	/** Bounded exact desired Activity plans referenced by immutable applied-plan bindings. */
	@Query(
		"SELECT * FROM source_desired_plan WHERE source_kind = :sourceKind " +
			"AND revision IN (:configurationRevisions) ORDER BY revision LIMIT :limit",
	)
	suspend fun portableDesiredPlans(
		sourceKind: Int,
		configurationRevisions: List<Long>,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	/** Bounded provider registrations referenced by terminal Activity completeness rows. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun portableProviderRegistrations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	/**
	 * Discovers one Activity-intent segment per logical entry without consulting sample_count.
	 *
	 * This deliberately admits a broad candidate when immutable intent or an Activity fact exists.
	 * The bounded product composer authenticates the complete manifest union and every fact; a
	 * relevant corrupt fact therefore becomes a typed failure instead of disappearing as not found.
	 * Candidate SQL never treats the absence of a fact as the absence of capture intent.
	 * The reader expands and authenticates every replacement member before returning product data.
	 */
	@Query(
		"""
		WITH activity_candidate_member AS (
		  SELECT segment.*
		  FROM session_segment AS segment
		  INNER JOIN source_service_run AS run
		    ON run.session_segment_id = segment.id
		   AND run.service_run_id = segment.service_run_id
		   AND run.logical_tracking_id = segment.logical_tracking_id
		  WHERE (
		    EXISTS (
		      SELECT 1
		      FROM session_manifest_version AS manifest
		      INNER JOIN session_manifest_source AS source
		        ON source.logical_tracking_id = manifest.logical_tracking_id
		       AND source.manifest_revision = manifest.manifest_revision
		      WHERE manifest.service_run_id = run.service_run_id
		        AND manifest.logical_tracking_id = run.logical_tracking_id
		        AND source.source_kind = :activitySourceKind
		        AND source.purpose = 'SESSION_CAPTURE'
		        AND source.persistence_eligible = 1
		    )
		    OR EXISTS (
		      SELECT 1
		      FROM activity_captured_window_revision AS fact
		      WHERE fact.logical_tracking_id = run.logical_tracking_id
		        AND fact.service_run_id = run.service_run_id
		        AND fact.session_segment_id = segment.id
		    )
		  )
		  UNION
		  SELECT segment.*
		  FROM session_segment AS segment
		  INNER JOIN activity_captured_window_revision AS fact
		    ON fact.session_segment_id = segment.id
		   AND fact.service_run_id = segment.service_run_id
		   AND fact.logical_tracking_id = segment.logical_tracking_id
		), logical_seed AS (
		  SELECT member.*
		  FROM activity_candidate_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1 FROM activity_candidate_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (
		        newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id)
		      )
		  )
		), ranked_seed AS (
		  SELECT seed.*,
		    (SELECT MAX(member_segment.start_time_ms)
		     FROM activity_candidate_member AS member_segment
		     WHERE member_segment.logical_tracking_id = seed.logical_tracking_id
		    ) AS logical_recency_start_ms,
		    (SELECT MAX(member_segment.id)
		     FROM activity_candidate_member AS member_segment
		     WHERE member_segment.logical_tracking_id = seed.logical_tracking_id
		       AND member_segment.start_time_ms = (
		         SELECT MAX(latest_segment.start_time_ms)
		         FROM activity_candidate_member AS latest_segment
		         WHERE latest_segment.logical_tracking_id = seed.logical_tracking_id
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
		activitySourceKind: Int,
	): List<ActivityLogicalHistoryCandidate>

	/**
	 * Bounded wall-range seeds ordered by the newest physical member tuple.
	 *
	 * Range overlap is decided from the complete logical member envelope before LIMIT. The caller
	 * must still expand and authenticate every replacement member before exposing product values.
	 */
	@Query(
		"""
		WITH activity_candidate AS (
		  SELECT DISTINCT run.logical_tracking_id
		  FROM source_service_run AS run
		  INNER JOIN session_segment AS segment
		    ON segment.id = run.session_segment_id
		   AND segment.service_run_id = run.service_run_id
		   AND segment.logical_tracking_id = run.logical_tracking_id
		  WHERE (
		    EXISTS (
		      SELECT 1
		      FROM session_manifest_version AS manifest
		      INNER JOIN session_manifest_source AS source
		        ON source.logical_tracking_id = manifest.logical_tracking_id
		       AND source.manifest_revision = manifest.manifest_revision
		      WHERE manifest.service_run_id = run.service_run_id
		        AND manifest.logical_tracking_id = run.logical_tracking_id
		        AND source.source_kind = :activitySourceKind
		        AND source.purpose = 'SESSION_CAPTURE'
		        AND source.persistence_eligible = 1
		    )
		    OR EXISTS (
		      SELECT 1
		      FROM activity_captured_window_revision AS fact
		      WHERE fact.logical_tracking_id = run.logical_tracking_id
		        AND fact.service_run_id = run.service_run_id
		        AND fact.session_segment_id = segment.id
		    )
		  )
		  UNION
		  SELECT DISTINCT fact.logical_tracking_id
		  FROM activity_captured_window_revision AS fact
		  INNER JOIN session_segment AS segment
		    ON segment.id = fact.session_segment_id
		   AND segment.service_run_id = fact.service_run_id
		   AND segment.logical_tracking_id = fact.logical_tracking_id
		), logical_member AS (
		  SELECT segment.*
		  FROM activity_candidate AS candidate
		  INNER JOIN session_segment AS segment
		    ON segment.logical_tracking_id = candidate.logical_tracking_id
		  WHERE EXISTS (
		    SELECT 1
		    FROM source_service_run AS run
		    WHERE run.logical_tracking_id = segment.logical_tracking_id
		      AND run.service_run_id = segment.service_run_id
		      AND run.session_segment_id = segment.id
		  ) OR EXISTS (
		    SELECT 1
		    FROM activity_captured_window_revision AS fact
		    WHERE fact.logical_tracking_id = segment.logical_tracking_id
		      AND fact.service_run_id = segment.service_run_id
		      AND fact.session_segment_id = segment.id
		  )
		), logical_bounds AS (
		  SELECT logical_tracking_id,
		         MIN(start_time_ms) AS logical_start_time_ms,
		         MAX(end_time_ms) AS logical_end_time_ms
		  FROM logical_member
		  GROUP BY logical_tracking_id
		), newest_member AS (
		  SELECT member.*
		  FROM logical_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1
		    FROM logical_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (
		        newer.start_time_ms > member.start_time_ms OR
		        (newer.start_time_ms = member.start_time_ms AND newer.id > member.id)
		      )
		  )
		)
		SELECT newest_member.*,
		       logical_bounds.logical_start_time_ms,
		       logical_bounds.logical_end_time_ms,
		       newest_member.start_time_ms AS logical_recency_start_ms,
		       newest_member.id AS logical_recency_segment_id
		FROM newest_member
		INNER JOIN logical_bounds
		  ON logical_bounds.logical_tracking_id = newest_member.logical_tracking_id
		WHERE logical_bounds.logical_start_time_ms < :toExclusiveMs
		  AND logical_bounds.logical_end_time_ms > :fromInclusiveMs
		  AND (
		    :beforeRecencyStartTimeMs IS NULL
		    OR newest_member.start_time_ms < :beforeRecencyStartTimeMs
		    OR (
		      newest_member.start_time_ms = :beforeRecencyStartTimeMs
		      AND newest_member.id < COALESCE(:beforeRecencySegmentId, 9223372036854775807)
		    )
		  )
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun logicalHistoryRangeCandidatePage(
		activitySourceKind: Int,
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencySegmentId: Long?,
	): List<ActivityLogicalRangeCandidate>

	/** Compact local Activity mutation fingerprint for opaque continuation invalidation. */
	@Query(
		"""
		WITH activity_logical AS (
		  SELECT DISTINCT logical_tracking_id
		  FROM session_manifest_source
		  WHERE source_kind = :activitySourceKind
		    AND purpose = 'SESSION_CAPTURE'
		    AND persistence_eligible = 1
		  UNION
		  SELECT DISTINCT logical_tracking_id
		  FROM activity_captured_window_revision
		), activity_runs AS (
		  SELECT service_run_id
		  FROM source_service_run
		  WHERE logical_tracking_id IN (SELECT logical_tracking_id FROM activity_logical)
		)
		SELECT
		  (SELECT COUNT(*) FROM logical_tracking_session
		   WHERE logical_tracking_id IN (SELECT logical_tracking_id FROM activity_logical)) AS
		    logical_session_count,
		  (SELECT COUNT(*) FROM activity_runs) AS service_run_count,
		  (SELECT COUNT(*) FROM session_segment
		   WHERE logical_tracking_id IN (SELECT logical_tracking_id FROM activity_logical)) AS
		    session_segment_count,
		  (SELECT COUNT(*) FROM session_manifest_version
		   WHERE service_run_id IN (SELECT service_run_id FROM activity_runs)) AS manifest_count,
		  (SELECT COUNT(*) FROM session_manifest_source
		   WHERE logical_tracking_id IN (SELECT logical_tracking_id FROM activity_logical)
		  ) AS
		    activity_manifest_source_count,
		  (SELECT COUNT(*) FROM source_policy WHERE source_kind = :activitySourceKind) AS policy_count,
		  (SELECT COUNT(*) FROM source_consent_epoch WHERE source_kind = :activitySourceKind) AS
		    consent_count,
		  (SELECT COUNT(*) FROM provider_registration_generation
		   WHERE source_kind = :activitySourceKind) AS provider_count,
		  (SELECT COUNT(*) FROM source_authorization
		   WHERE source_kind = :activitySourceKind) AS authorization_count,
		  (SELECT COUNT(*) FROM source_demand WHERE source_kind = :activitySourceKind) AS demand_count,
		  (SELECT COUNT(*) FROM source_session_completeness
		   WHERE source_kind = :activitySourceKind) AS completeness_count,
		  (SELECT COUNT(*) FROM source_product_projection_lane
		   WHERE source_kind = :activitySourceKind) AS lane_count,
		  (SELECT COUNT(*) FROM source_deletion_fence
		   WHERE source_kind = :activitySourceKind) AS deletion_fence_count,
		  (SELECT COUNT(*) FROM activity_captured_registration_plan) AS registration_plan_count,
		  (SELECT COUNT(*) FROM source_desired_plan
		   WHERE source_kind = :activitySourceKind) AS desired_plan_count,
		  (SELECT COUNT(*) FROM activity_captured_window_revision) AS revision_count,
		  (SELECT COUNT(*) FROM activity_captured_window_cursor) AS cursor_count,
		  (SELECT COUNT(*) FROM activity_captured_fragment) AS fragment_count,
		  (SELECT COUNT(*) FROM activity_captured_evidence) AS evidence_count
		""",
	)
	suspend fun productRevisionSnapshot(
		activitySourceKind: Int,
	): ActivityProductRevisionSnapshot

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

	/** Source-filtered completeness prevents unrelated sources from consuming the overflow probe. */
	@Query(
		"SELECT * FROM source_session_completeness WHERE source_kind = :sourceKind " +
			"AND service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, source_instance_id, registration_generation LIMIT :limit",
	)
	suspend fun portableCompleteness(
		sourceKind: Int,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourceSessionCompletenessEntity>

	/** Payload-free retained Activity WAL rows used to prove each selected run's true high-water. */
	@Query(
		"SELECT admission_ordinal, event_id, provider_dedup_key, delivery_identity, " +
			"delivery_unit_index, delivery_unit_count, logical_tracking_id, service_run_id, " +
			"source_kind, source_instance_id, registration_generation, " +
			"physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, source_sequence, " +
			"config_revision, plan_attribution, clock_domain_id, observed_elapsed_nanos, " +
			"observed_interval_start_nanos, received_elapsed_nanos, wall_time_ms, " +
			"wall_time_uncertainty_ms, captured_collected_data_epoch, activity_automation_epoch, " +
			"source_policy_revision, capture_consent_epoch, session_manifest_revision, " +
			"lifecycle_lease_generation, acquired_at_ms, quality_flags, quality_confidence, " +
			"payload_version, payload_checksum, LENGTH(payload) AS payload_bytes, " +
			"integrity_identity, created_at_ms " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, admission_ordinal LIMIT :limit",
	)
	suspend fun portableCapturedWalTargets(
		sourceKind: Int,
		serviceRunIds: List<String>,
		limit: Int,
	): List<ActivityCapturedPortableWalTargetRow>

	/** Complete payload-free delivery siblings for bounded selected-run Activity WAL targets. */
	@Query(
		"SELECT admission_ordinal, event_id, provider_dedup_key, delivery_identity, " +
			"delivery_unit_index, delivery_unit_count, logical_tracking_id, service_run_id, " +
			"source_kind, source_instance_id, registration_generation, " +
			"physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, source_sequence, " +
			"config_revision, plan_attribution, clock_domain_id, observed_elapsed_nanos, " +
			"observed_interval_start_nanos, received_elapsed_nanos, wall_time_ms, " +
			"wall_time_uncertainty_ms, captured_collected_data_epoch, activity_automation_epoch, " +
			"source_policy_revision, capture_consent_epoch, session_manifest_revision, " +
			"lifecycle_lease_generation, acquired_at_ms, quality_flags, quality_confidence, " +
			"payload_version, payload_checksum, LENGTH(payload) AS payload_bytes, " +
			"integrity_identity, created_at_ms " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND delivery_identity IN (:deliveryIdentities) " +
			"ORDER BY delivery_identity, admission_ordinal LIMIT :limit",
	)
	suspend fun portableCapturedWalDeliveryMembers(
		sourceKind: Int,
		deliveryIdentities: List<String>,
		limit: Int,
	): List<ActivityCapturedPortableWalTargetRow>

	/** Bounded immutable lifecycle acknowledgements binding a provider generation to selected runs. */
	@Query(
		"SELECT * FROM lifecycle_desired_action WHERE source_kind = :sourceKind " +
			"AND service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, action_revision LIMIT :limit",
	)
	suspend fun portableLifecycleActions(
		sourceKind: Int,
		serviceRunIds: List<String>,
		limit: Int,
	): List<LifecycleDesiredActionEntity>

	/**
	 * Complete bounded authorization history for the selected physical Activity generations.
	 *
	 * CONTROL and deny-all successors are deliberately retained: they close the provider-time
	 * interval of an earlier capture revision and therefore cannot be filtered by run or purpose.
	 */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"ORDER BY registration_generation, effective_elapsed_realtime_nanos, " +
			"authorization_revision, member_id LIMIT :limit",
	)
	suspend fun portableAuthorizationsForGenerations(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		limit: Int,
	): List<SourceAuthorizationEntity>

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

	/** Nonterminal captured demands include prepared rows that could later become active. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND purpose = :purpose AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun capturedActivityDemandsForDeletion(
		sourceKind: Int,
		purpose: String,
		limit: Int,
	): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind AND purpose = :purpose " +
			"AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED') " +
			"AND (logical_tracking_id = :logicalTrackingId OR service_run_id IN (:serviceRunIds)) " +
			"ORDER BY demand_id LIMIT :limit",
	)
	suspend fun selectedCapturedActivityDemands(
		sourceKind: Int,
		purpose: String,
		logicalTrackingId: String,
		serviceRunIds: List<String>,
		limit: Int,
	): List<SourceDemandEntity>

	/** All physical registrations that could still deliver a captured callback. */
	/** Latest immutable authorization revision identity without materializing its members. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING') " +
			"ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun activityRegistrationsForDeletion(
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
		"SELECT MAX(authorization_revision) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration",
	)
	suspend fun latestAuthorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long?

	@Query(
		"SELECT * FROM source_desired_plan WHERE revision = :revision " +
			"ORDER BY source_kind LIMIT :limit",
	)
	suspend fun maintenanceDesiredPlans(
		revision: Long,
		limit: Int,
	): List<SourceDesiredPlanEntity>

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_revision WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedRevisionCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_fragment WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedFragmentCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_evidence WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedEvidenceCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

	@Query(
		"SELECT COUNT(*) FROM activity_captured_window_cursor WHERE " +
			"writer_projection_id != :writerProjectionId " +
			"OR writer_projection_version != :writerProjectionVersion",
	)
	suspend fun unsupportedCursorCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
	): Long

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

	@Query("SELECT COUNT(*) FROM activity_captured_fragment")
	suspend fun fragmentCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_evidence")
	suspend fun evidenceCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_window_cursor")
	suspend fun cursorCount(): Long

	@Query("SELECT COUNT(*) FROM activity_captured_registration_plan")
	suspend fun registrationPlanBindingCount(): Long

	@Query(
		"DELETE FROM activity_captured_window_cursor " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun deleteExactCursors(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowIds: List<String>,
	): Int

	@Query(
		"DELETE FROM activity_captured_window_revision " +
			"WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_window_id IN (:logicalWindowIds)",
	)
	suspend fun deleteExactRevisionLineages(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalWindowIds: List<String>,
	): Int

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

/** Minimal source-owned WAL header; payload bytes and provider identifiers never leave Room. */
data class ActivityCapturedPortableWalTargetRow(
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "provider_dedup_key") val providerDedupKey: String?,
	@ColumnInfo(name = "delivery_identity") val deliveryIdentity: String?,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int?,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint")
	val physicalConfigurationFingerprint: String?,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long?,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask")
	val authorizationPurposeEligibilityMask: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String?,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "config_revision") val configRevision: Long?,
	@ColumnInfo(name = "plan_attribution") val planAttribution: Int,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "observed_elapsed_nanos") val observedElapsedNanos: Long,
	@ColumnInfo(name = "observed_interval_start_nanos") val observedIntervalStartNanos: Long?,
	@ColumnInfo(name = "received_elapsed_nanos") val receivedElapsedNanos: Long,
	@ColumnInfo(name = "wall_time_ms") val wallTimeMs: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "captured_collected_data_epoch") val capturedCollectedDataEpoch: Long,
	@ColumnInfo(name = "activity_automation_epoch") val activityAutomationEpoch: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "session_manifest_revision") val sessionManifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "acquired_at_ms") val acquiredAtMs: Long,
	@ColumnInfo(name = "quality_flags") val qualityFlags: Long,
	@ColumnInfo(name = "quality_confidence") val qualityConfidence: Float?,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "payload_bytes") val payloadBytes: Long,
	@ColumnInfo(name = "integrity_identity") val integrityIdentity: String,
	@ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** Intent-backed seed plus the newest reciprocal member used only for stable keyset ordering. */
data class ActivityLogicalHistoryCandidate(
	@Embedded val segment: SessionSegment,
	@ColumnInfo(name = "logical_recency_start_ms") val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id") val logicalRecencySegmentId: Long,
)

data class ActivityLogicalRangeCandidate(
	@Embedded val segment: SessionSegment,
	@ColumnInfo(name = "logical_start_time_ms") val logicalStartTimeMs: Long,
	@ColumnInfo(name = "logical_end_time_ms") val logicalEndTimeMs: Long,
	@ColumnInfo(name = "logical_recency_start_ms") val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id") val logicalRecencySegmentId: Long,
)

data class ActivityProductRevisionSnapshot(
	@ColumnInfo(name = "logical_session_count") val logicalSessionCount: Long,
	@ColumnInfo(name = "service_run_count") val serviceRunCount: Long,
	@ColumnInfo(name = "session_segment_count") val sessionSegmentCount: Long,
	@ColumnInfo(name = "manifest_count") val manifestCount: Long,
	@ColumnInfo(name = "activity_manifest_source_count") val activityManifestSourceCount: Long,
	@ColumnInfo(name = "policy_count") val policyCount: Long,
	@ColumnInfo(name = "consent_count") val consentCount: Long,
	@ColumnInfo(name = "provider_count") val providerCount: Long,
	@ColumnInfo(name = "authorization_count") val authorizationCount: Long,
	@ColumnInfo(name = "demand_count") val demandCount: Long,
	@ColumnInfo(name = "completeness_count") val completenessCount: Long,
	@ColumnInfo(name = "lane_count") val laneCount: Long,
	@ColumnInfo(name = "deletion_fence_count") val deletionFenceCount: Long,
	@ColumnInfo(name = "registration_plan_count") val registrationPlanCount: Long,
	@ColumnInfo(name = "desired_plan_count") val desiredPlanCount: Long,
	@ColumnInfo(name = "revision_count") val revisionCount: Long,
	@ColumnInfo(name = "cursor_count") val cursorCount: Long,
	@ColumnInfo(name = "fragment_count") val fragmentCount: Long,
	@ColumnInfo(name = "evidence_count") val evidenceCount: Long,
) {
	init {
		require(
			listOf(
				logicalSessionCount,
				serviceRunCount,
				sessionSegmentCount,
				manifestCount,
				activityManifestSourceCount,
				policyCount,
				consentCount,
				providerCount,
				authorizationCount,
				demandCount,
				completenessCount,
				laneCount,
				deletionFenceCount,
				registrationPlanCount,
				desiredPlanCount,
				revisionCount,
				cursorCount,
				fragmentCount,
				evidenceCount,
			).all { it >= 0L },
		)
	}
}
