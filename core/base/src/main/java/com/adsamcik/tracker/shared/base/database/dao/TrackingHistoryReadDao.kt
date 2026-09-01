package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity

/**
 * Fixed-count batch reads for composing source-qualified tracking history.
 *
 * This DAO deliberately owns no mutation and no persisted summary. Product readers verify the
 * immutable manifests and source-specific facts from one Room transaction instead of issuing one
 * observer or query cascade per presentation row.
 */
@Dao
interface TrackingHistoryReadDao {
	/**
	 * Evidence-bearing logical-entry candidate page.
	 *
	 * Exactly bound attributed rows and attributed migrated rows are grouped by their explicit logical
	 * id before [limit] is applied. Only null/null unattributed legacy rows use their physical segment
	 * id. A coarse evidence-bearing member discovers the identity; entry recency then uses its newest
	 * membership-eligible physical sibling with a stable physical-id tiebreaker. Kotlin still verifies
	 * checksums, epoch, fence, lane, completeness, and every physical member, so the caller must keyset
	 * until enough qualified entries are filled.
	 */
	@Query(
		"""
		WITH classified_segment AS (
		  SELECT segment.id,
			 segment.start_time_ms,
			 segment.logical_tracking_id,
			 CASE WHEN (
			   segment.sample_count > 0
			   AND (
				 (
				   segment.logical_tracking_id IS NULL
				   AND segment.service_run_id IS NULL
				 )
				 OR EXISTS (
				   SELECT 1
				   FROM source_service_run AS legacy_run
				   WHERE legacy_run.service_run_id = segment.service_run_id
					 AND legacy_run.logical_tracking_id = segment.logical_tracking_id
					 AND legacy_run.presentation_acknowledgement = 'LEGACY_UNVERIFIABLE'
				 )
			   )
			 ) THEN 1 ELSE 0 END AS legacy_compatibility,
			 CASE WHEN EXISTS (
			   SELECT 1
			   FROM source_service_run AS run
			   JOIN session_manifest_version AS manifest
				 ON manifest.service_run_id = run.service_run_id
				AND manifest.logical_tracking_id = run.logical_tracking_id
			   JOIN session_manifest_source AS source
				 ON source.logical_tracking_id = manifest.logical_tracking_id
				AND source.manifest_revision = manifest.manifest_revision
			   WHERE run.service_run_id = segment.service_run_id
				 AND run.logical_tracking_id = segment.logical_tracking_id
				 AND run.session_segment_id = segment.id
				 AND run.presentation_acknowledgement != 'LEGACY_UNVERIFIABLE'
				 AND source.source_kind = :stepsSourceKind
				 AND source.purpose = :capturePurpose
				 AND source.persistence_eligible = 1
				 AND (
				   (source.writer_owner = 'LEGACY_STEP_INTERVAL' AND segment.steps > 0)
				   OR (
					 source.writer_owner = 'STEPS_SESSION_FACTS'
					 AND EXISTS (
					   SELECT 1
					   FROM step_fact_revision AS fact
					   WHERE fact.logical_tracking_id = run.logical_tracking_id
						 AND fact.service_run_id = run.service_run_id
						 AND fact.manifest_revision = manifest.manifest_revision
						 AND fact.purpose = :capturePurpose
						 AND fact.writer_projection_id = source.writer_projection_id
						 AND fact.writer_projection_version = source.writer_projection_version
						 AND fact.writer_binding_generation = source.writer_binding_generation
						 AND fact.operation = 'UPSERT'
						 AND fact.coverage_kind = 'COVERED'
						 AND NOT EXISTS (
						   SELECT 1
						   FROM step_fact_revision AS newer
						   WHERE newer.writer_projection_id = fact.writer_projection_id
							 AND newer.writer_projection_version = fact.writer_projection_version
							 AND newer.logical_fact_id = fact.logical_fact_id
							 AND newer.semantic_revision > fact.semantic_revision
						 )
					 )
				   )
				 )
			 ) THEN 1 ELSE 0 END AS exact_candidate
		  FROM session_segment AS segment
		), coarse_candidate AS (
		  SELECT id,
			 start_time_ms,
			 CASE WHEN exact_candidate = 1 OR (
			   legacy_compatibility = 1
			   AND logical_tracking_id IS NOT NULL
			   AND logical_tracking_id != ''
			 ) THEN logical_tracking_id ELSE NULL END
			   AS logical_tracking_id,
			 CASE WHEN legacy_compatibility = 1 AND logical_tracking_id IS NULL
			   THEN id ELSE NULL END AS legacy_segment_id
		  FROM classified_segment
		  WHERE legacy_compatibility = 1 OR exact_candidate = 1
		), logical_candidate AS (
		  SELECT DISTINCT logical_tracking_id
		  FROM coarse_candidate
		  WHERE logical_tracking_id IS NOT NULL
		), candidate_member AS (
		  SELECT segment.id,
			 segment.start_time_ms,
			 logical_candidate.logical_tracking_id,
			 NULL AS legacy_segment_id
		  FROM logical_candidate
		  JOIN session_segment AS segment
			ON segment.logical_tracking_id = logical_candidate.logical_tracking_id
		  JOIN source_service_run AS run
			ON run.service_run_id = segment.service_run_id
		   AND run.logical_tracking_id = segment.logical_tracking_id
		  WHERE run.presentation_acknowledgement = 'LEGACY_UNVERIFIABLE'
			 OR run.session_segment_id = segment.id
		  UNION ALL
		  SELECT id,
			 start_time_ms,
			 NULL AS logical_tracking_id,
			 legacy_segment_id
		  FROM coarse_candidate
		  WHERE legacy_segment_id IS NOT NULL
		), candidate_latest AS (
		  SELECT logical_tracking_id,
			 legacy_segment_id,
			 MAX(start_time_ms) AS sort_start_time_ms
		  FROM candidate_member
		  GROUP BY logical_tracking_id, legacy_segment_id
		), grouped_candidate AS (
		  SELECT candidate_latest.logical_tracking_id,
			 candidate_latest.legacy_segment_id,
			 candidate_latest.sort_start_time_ms,
			 MAX(candidate_member.id) AS sort_segment_id
		  FROM candidate_latest
		  JOIN candidate_member
			ON candidate_member.start_time_ms = candidate_latest.sort_start_time_ms
		   AND (
			 (
			   candidate_latest.logical_tracking_id IS NOT NULL
			   AND candidate_member.logical_tracking_id = candidate_latest.logical_tracking_id
			 ) OR (
			   candidate_latest.logical_tracking_id IS NULL
			   AND candidate_member.logical_tracking_id IS NULL
			   AND candidate_member.legacy_segment_id = candidate_latest.legacy_segment_id
			 )
		   )
		  GROUP BY candidate_latest.logical_tracking_id,
			   candidate_latest.legacy_segment_id,
			   candidate_latest.sort_start_time_ms
		)
		SELECT *
		FROM grouped_candidate
		WHERE :beforeStartTimeMs IS NULL
		   OR sort_start_time_ms < :beforeStartTimeMs
		   OR (
			 sort_start_time_ms = :beforeStartTimeMs
			 AND sort_segment_id < COALESCE(:beforeSegmentId, 9223372036854775807)
		   )
		ORDER BY sort_start_time_ms DESC, sort_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun recentEntryCandidatePage(
		limit: Int,
		stepsSourceKind: Int,
		capturePurpose: String,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<RecentHistoryEntryCandidate>

	/** Reads the requested physical presentation rows without changing their order contract. */
	@Query("SELECT * FROM session_segment WHERE id IN (:segmentIds)")
	suspend fun segments(segmentIds: List<Long>): List<SessionSegment>

	/** Keyset page of exact or explicitly attributed migrated siblings for logical entries. */
	@Query(
		"SELECT segment.* FROM session_segment AS segment " +
			"JOIN source_service_run AS run ON run.service_run_id = segment.service_run_id " +
			"AND run.logical_tracking_id = segment.logical_tracking_id " +
			"WHERE segment.logical_tracking_id IN (:logicalTrackingIds) " +
			"AND (run.presentation_acknowledgement = 'LEGACY_UNVERIFIABLE' " +
			"OR run.session_segment_id = segment.id) " +
			"AND (:afterStartTimeMs IS NULL OR segment.start_time_ms > :afterStartTimeMs " +
			"OR (segment.start_time_ms = :afterStartTimeMs " +
			"AND segment.id > COALESCE(:afterSegmentId, 0))) " +
			"ORDER BY segment.start_time_ms, segment.id LIMIT :limit",
	)
	suspend fun logicalEntrySegmentPage(
		logicalTrackingIds: List<String>,
		limit: Int,
		afterStartTimeMs: Long?,
		afterSegmentId: Long?,
	): List<SessionSegment>

	/** Reads the service-run membership needed by one bounded presentation batch. */
	@Query("SELECT * FROM source_service_run WHERE service_run_id IN (:serviceRunIds)")
	suspend fun serviceRuns(serviceRunIds: List<String>): List<SourceServiceRunEntity>

	/** Reads all immutable manifest revisions for the requested service runs. */
	@Query(
		"SELECT * FROM session_manifest_version WHERE service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, manifest_revision LIMIT :limit",
	)
	suspend fun manifests(
		serviceRunIds: List<String>,
		limit: Int = Int.MAX_VALUE,
	): List<SessionManifestVersionEntity>

	/** Reads exact source/purpose membership for the requested runs' manifests. */
	@Query(
		"SELECT source.* FROM session_manifest_source AS source " +
			"JOIN session_manifest_version AS manifest " +
			"ON manifest.logical_tracking_id = source.logical_tracking_id " +
			"AND manifest.manifest_revision = source.manifest_revision " +
			"WHERE manifest.service_run_id IN (:serviceRunIds) " +
			"ORDER BY source.logical_tracking_id, source.manifest_revision, " +
			"source.purpose, source.source_kind LIMIT :limit",
	)
	suspend fun manifestSources(
		serviceRunIds: List<String>,
		limit: Int = Int.MAX_VALUE,
	): List<SessionManifestSourceEntity>

	/** Reads only the source-policy revisions referenced by the requested runs. */
	@Query(
		"SELECT DISTINCT policy.* FROM source_policy AS policy " +
			"JOIN session_manifest_version AS manifest " +
			"ON manifest.source_policy_revision = policy.policy_revision " +
			"WHERE policy.source_kind = :sourceKind " +
			"AND manifest.service_run_id IN (:serviceRunIds)",
	)
	suspend fun policiesForServiceRuns(
		sourceKind: Int,
		serviceRunIds: List<String>,
	): List<SourcePolicyEntity>

	/** Reads source-local acquisition settlement for the requested service runs. */
	@Query(
		"SELECT * FROM source_session_completeness WHERE service_run_id IN (:serviceRunIds) " +
			"ORDER BY service_run_id, source_kind, source_instance_id, registration_generation " +
			"LIMIT :limit",
	)
	suspend fun completeness(
		serviceRunIds: List<String>,
		limit: Int = Int.MAX_VALUE,
	): List<SourceSessionCompletenessEntity>

	/**
	 * Resolves each fact through its latest UPSERT attribution, then returns its fact-global latest
	 * semantic state. The scoped run column survives a redacted RETRACT whose own session fields are
	 * intentionally null.
	 */
	@Query(
		"""
		WITH latest_upsert AS (
			SELECT upsert.*
			FROM step_fact_revision AS upsert
			WHERE upsert.operation = 'UPSERT'
			  AND NOT EXISTS (
				SELECT 1
				FROM step_fact_revision AS newer_upsert
				WHERE newer_upsert.writer_projection_id = upsert.writer_projection_id
				  AND newer_upsert.writer_projection_version = upsert.writer_projection_version
				  AND newer_upsert.logical_fact_id = upsert.logical_fact_id
				  AND newer_upsert.operation = 'UPSERT'
				  AND newer_upsert.semantic_revision > upsert.semantic_revision
			  )
		), scoped_fact AS (
			SELECT latest_upsert.writer_projection_id,
			       latest_upsert.writer_projection_version,
			       latest_upsert.logical_fact_id,
			       latest_upsert.service_run_id AS scoped_service_run_id,
			       latest_upsert.logical_tracking_id AS scoped_logical_tracking_id,
			       latest_upsert.manifest_revision AS scoped_manifest_revision,
			       latest_upsert.writer_binding_generation AS scoped_writer_binding_generation,
			       latest_upsert.interval_start_time_ms AS first_interval_start_time_ms
			FROM latest_upsert
			WHERE latest_upsert.service_run_id IN (:serviceRunIds)
			  AND latest_upsert.purpose = 'SESSION_CAPTURE'
		)
		SELECT scoped_fact.scoped_service_run_id,
		       scoped_fact.scoped_logical_tracking_id,
		       scoped_fact.scoped_manifest_revision,
		       scoped_fact.scoped_writer_binding_generation,
		       state.*
		FROM scoped_fact
		JOIN step_fact_revision AS state
		  ON state.writer_projection_id = scoped_fact.writer_projection_id
		 AND state.writer_projection_version = scoped_fact.writer_projection_version
		 AND state.logical_fact_id = scoped_fact.logical_fact_id
		WHERE state.semantic_revision = (
			SELECT MAX(newer.semantic_revision)
			FROM step_fact_revision AS newer
			WHERE newer.writer_projection_id = scoped_fact.writer_projection_id
			  AND newer.writer_projection_version = scoped_fact.writer_projection_version
			  AND newer.logical_fact_id = scoped_fact.logical_fact_id
		)
		ORDER BY scoped_fact.scoped_service_run_id,
		         scoped_fact.first_interval_start_time_ms,
		         scoped_fact.logical_fact_id
		LIMIT :limit
		""",
	)
	suspend fun stepFactStates(
		serviceRunIds: List<String>,
		limit: Int = Int.MAX_VALUE,
	): List<ScopedStepFactState>

	/** Reads durable source-local deletion fences for the exact requested scope digests. */
	@Query(
		"SELECT * FROM source_deletion_fence WHERE source_kind = :sourceKind " +
			"AND purpose = :purpose AND scope_kind = :scopeKind " +
			"AND scope_identity_digest IN (:scopeIdentityDigests)",
	)
	suspend fun deletionFences(
		sourceKind: Int,
		purpose: String,
		scopeKind: String,
		scopeIdentityDigests: List<String>,
	): List<SourceDeletionFenceEntity>

	/** Reads Steps product lanes referenced by the requested runs' immutable writer bindings. */
	@Query(
		"""
		SELECT lane.*
		FROM source_product_projection_lane AS lane
		WHERE lane.source_kind = :sourceKind
		  AND EXISTS (
			SELECT 1
			FROM session_manifest_source AS source
			JOIN session_manifest_version AS manifest
			  ON manifest.logical_tracking_id = source.logical_tracking_id
			 AND manifest.manifest_revision = source.manifest_revision
			WHERE manifest.service_run_id IN (:serviceRunIds)
			  AND source.source_kind = :sourceKind
			  AND source.purpose = :capturePurpose
			  AND source.persistence_eligible = 1
			  AND source.writer_projection_id = lane.projection_id
			  AND source.writer_projection_version = lane.projection_version
			  AND source.writer_binding_generation = lane.binding_generation
		  )
		ORDER BY lane.binding_generation, lane.projection_id, lane.projection_version
		""",
	)
	suspend fun productLanesForServiceRuns(
		sourceKind: Int,
		capturePurpose: String,
		serviceRunIds: List<String>,
	): List<SourceProductProjectionLaneEntity>

	/** Reads relevant terminal failures only for referenced Steps writers and ordinal bounds. */
	@Query(
		"""
		SELECT failure.*
		FROM source_projection_failure AS failure
		WHERE failure.terminal = 1
		  AND failure.admission_ordinal > :afterOrdinal
		  AND failure.admission_ordinal <= :throughOrdinal
		  AND EXISTS (
			SELECT 1
			FROM session_manifest_source AS source
			JOIN session_manifest_version AS manifest
			  ON manifest.logical_tracking_id = source.logical_tracking_id
			 AND manifest.manifest_revision = source.manifest_revision
			WHERE manifest.service_run_id IN (:serviceRunIds)
			  AND source.source_kind = :sourceKind
			  AND source.purpose = :capturePurpose
			  AND source.persistence_eligible = 1
			  AND source.writer_projection_id = failure.projection_id
			  AND source.writer_projection_version = failure.projection_version
		  )
		ORDER BY failure.admission_ordinal,
		         failure.projection_id,
		         failure.projection_version
		LIMIT :limit
		""",
	)
	suspend fun terminalFailuresForServiceRuns(
		sourceKind: Int,
		capturePurpose: String,
		serviceRunIds: List<String>,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceProjectionFailureEntity>
}

/** Stable keyset cursor and identity for one coarse evidence-bearing logical history entry. */
data class RecentHistoryEntryCandidate(
	@ColumnInfo(name = "logical_tracking_id")
	val logicalTrackingId: String?,
	@ColumnInfo(name = "legacy_segment_id")
	val legacySegmentId: Long?,
	@ColumnInfo(name = "sort_start_time_ms")
	val sortStartTimeMs: Long,
	@ColumnInfo(name = "sort_segment_id")
	val sortSegmentId: Long,
)

/** Latest global state for a fact together with the service-run scope of its latest UPSERT. */
data class ScopedStepFactState(
	@ColumnInfo(name = "scoped_service_run_id")
	val serviceRunId: String,
	@ColumnInfo(name = "scoped_logical_tracking_id")
	val logicalTrackingId: String,
	@ColumnInfo(name = "scoped_manifest_revision")
	val manifestRevision: Long,
	@ColumnInfo(name = "scoped_writer_binding_generation")
	val writerBindingGeneration: Long,
	@Embedded
	val state: StepFactRevisionEntity,
)
