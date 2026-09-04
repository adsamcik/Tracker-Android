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
@Suppress("LargeClass") // Cohesive batched history reads; a second Room DAO has no independent owner.
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

	/**
	 * Keyset page of all bound and unbound physical runs overlapping or starting within
	 * `[fromMs, toMs)`.
	 *
	 * Cursor keys must both be null for the first page or identify the final row from the preceding
	 * page. Including starts whose completion wall regressed lets Kotlin fail closed instead of
	 * silently hiding them. Presentation ownership is deliberately not a selection predicate.
	 */
	@Query(
		"""
		SELECT * FROM source_service_run
		WHERE started_at_ms < :toMs
		  AND (started_at_ms >= :fromMs OR completed_at_ms IS NULL OR completed_at_ms > :fromMs)
		  AND (
			:afterStartedAtMs IS NULL
			OR started_at_ms > :afterStartedAtMs
			OR (
			  started_at_ms = :afterStartedAtMs
			  AND service_run_id > COALESCE(:afterServiceRunId, '')
			)
		  )
		ORDER BY started_at_ms, service_run_id
		LIMIT :limit
		""",
	)
	suspend fun serviceRunCandidatePage(
		fromMs: Long,
		toMs: Long,
		limit: Int,
		afterStartedAtMs: Long?,
		afterServiceRunId: String?,
	): List<SourceServiceRunEntity>

	/**
	 * Keyset page of run identities discovered from each fact's latest UPSERT wall projection.
	 *
	 * This is discovery only: callers must still authenticate the returned run, manifest, writer,
	 * and source-local elapsed timeline. It deliberately does not filter purpose or writer identity,
	 * so malformed overlapping evidence cannot hide behind a clock-shifted run envelope.
	 */
	@Query(
		"""
		SELECT DISTINCT fact.service_run_id
		FROM step_fact_revision AS fact
		WHERE fact.operation = 'UPSERT'
		  AND fact.service_run_id IS NOT NULL
		  AND fact.interval_end_time_ms >= :fromMs
		  AND fact.interval_start_time_ms < :toMs
		  AND NOT EXISTS (
			SELECT 1
			FROM step_fact_revision AS newer_upsert
			WHERE newer_upsert.writer_projection_id = fact.writer_projection_id
			  AND newer_upsert.writer_projection_version = fact.writer_projection_version
			  AND newer_upsert.logical_fact_id = fact.logical_fact_id
			  AND newer_upsert.operation = 'UPSERT'
			  AND newer_upsert.semantic_revision > fact.semantic_revision
		  )
		  AND (:afterServiceRunId IS NULL OR fact.service_run_id > :afterServiceRunId)
		ORDER BY fact.service_run_id
		LIMIT :limit
		""",
	)
	suspend fun stepFactServiceRunCandidateIdPage(
		fromMs: Long,
		toMs: Long,
		limit: Int,
		afterServiceRunId: String?,
	): List<String>

	/**
	 * Keyset page of presentation segments owned by or claiming runs with durable Steps evidence.
	 *
	 * Source membership is filtered before paging so unrelated source rows cannot consume a Steps
	 * reader's dependency budget. A manifest membership keeps a still-materializing Steps run
	 * discoverable before its first fact. A source-local fact remains discovery evidence when its
	 * manifest is malformed or missing, allowing the caller to fail closed instead of hiding it.
	 * Kotlin still validates the segment's reciprocal logical/run identity.
	 */
	@Query(
		"""
		SELECT segment.*
		FROM session_segment AS segment
		WHERE segment.end_time_ms > :fromMs
		  AND segment.start_time_ms < :toMs
		  AND EXISTS (
			SELECT 1
			FROM source_service_run AS run
			WHERE (
			  run.session_segment_id = segment.id
			  OR run.service_run_id = segment.service_run_id
			)
			AND (
			  EXISTS (
				SELECT 1
				FROM session_manifest_version AS manifest
				JOIN session_manifest_source AS source
				  ON source.logical_tracking_id = manifest.logical_tracking_id
				 AND source.manifest_revision = manifest.manifest_revision
				WHERE manifest.service_run_id = run.service_run_id
				  AND source.source_kind = :stepsSourceKind
				  AND source.purpose = :capturePurpose
				  AND source.persistence_eligible = 1
			  ) OR EXISTS (
				SELECT 1
				FROM step_fact_revision AS fact
				WHERE fact.service_run_id = run.service_run_id
				  AND fact.purpose = :capturePurpose
			  )
			)
		  )
		  AND (
			:afterStartTimeMs IS NULL
			OR segment.start_time_ms > :afterStartTimeMs
			OR (
			  segment.start_time_ms = :afterStartTimeMs
			  AND segment.id > COALESCE(:afterSegmentId, 0)
			)
		  )
		ORDER BY segment.start_time_ms, segment.id
		LIMIT :limit
		""",
	)
	suspend fun portableStepsSegmentCandidatePage(
		fromMs: Long,
		toMs: Long,
		stepsSourceKind: Int,
		capturePurpose: String,
		limit: Int,
		afterStartTimeMs: Long?,
		afterSegmentId: Long?,
	): List<SessionSegment>

	/**
	 * Keyset page of bound or unbound runs carrying durable Steps evidence in `[fromMs, toMs)`.
	 *
	 * This is discovery only. The caller expands every physical replacement run through explicit
	 * logical membership and validates its complete immutable attribution independently.
	 */
	@Query(
		"""
		SELECT run.*
		FROM source_service_run AS run
		WHERE run.started_at_ms < :toMs
		  AND (
			run.started_at_ms >= :fromMs
			OR run.completed_at_ms IS NULL
			OR run.completed_at_ms > :fromMs
		  )
		  AND (
			EXISTS (
			  SELECT 1
			  FROM session_manifest_version AS manifest
			  JOIN session_manifest_source AS source
				ON source.logical_tracking_id = manifest.logical_tracking_id
			   AND source.manifest_revision = manifest.manifest_revision
			  WHERE manifest.service_run_id = run.service_run_id
				AND source.source_kind = :stepsSourceKind
				AND source.purpose = :capturePurpose
				AND source.persistence_eligible = 1
			) OR EXISTS (
			  SELECT 1
			  FROM step_fact_revision AS fact
			  WHERE fact.service_run_id = run.service_run_id
				AND fact.purpose = :capturePurpose
			)
		  )
		  AND (
			:afterStartedAtMs IS NULL
			OR run.started_at_ms > :afterStartedAtMs
			OR (
			  run.started_at_ms = :afterStartedAtMs
			  AND run.service_run_id > COALESCE(:afterServiceRunId, '')
			)
		  )
		ORDER BY run.started_at_ms, run.service_run_id
		LIMIT :limit
		""",
	)
	suspend fun portableStepsServiceRunCandidatePage(
		fromMs: Long,
		toMs: Long,
		stepsSourceKind: Int,
		capturePurpose: String,
		limit: Int,
		afterStartedAtMs: Long?,
		afterServiceRunId: String?,
	): List<SourceServiceRunEntity>

	/**
	 * Keyset page of every physical replacement run for the requested logical entries.
	 *
	 * This expands only explicit [SourceServiceRunEntity.logicalTrackingId] membership. Wall-time
	 * overlap is deliberately absent: callers may use time only to discover a logical entry, then
	 * must validate every returned run and its exact presentation reverse binding independently.
	 */
	@Query(
		"""
		SELECT * FROM source_service_run
		WHERE logical_tracking_id IN (:logicalTrackingIds)
		  AND (
			:afterLogicalTrackingId IS NULL
			OR logical_tracking_id > :afterLogicalTrackingId
			OR (
			  logical_tracking_id = :afterLogicalTrackingId
			  AND (
				started_at_ms > COALESCE(:afterStartedAtMs, -1)
				OR (
				  started_at_ms = COALESCE(:afterStartedAtMs, -1)
				  AND service_run_id > COALESCE(:afterServiceRunId, '')
				)
			  )
			)
		  )
		ORDER BY logical_tracking_id, started_at_ms, service_run_id
		LIMIT :limit
		""",
	)
	suspend fun logicalEntryServiceRunPage(
		logicalTrackingIds: List<String>,
		limit: Int,
		afterLogicalTrackingId: String?,
		afterStartedAtMs: Long?,
		afterServiceRunId: String?,
	): List<SourceServiceRunEntity>

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
	 * Anchors each candidate fact to a requested durable run before returning its retained scope
	 * carrier and fact-global state.
	 *
	 * Run id and logical id are provisional until the complete UPSERT checksum is validated. A third,
	 * independent candidate relation uses source-local registration-generation high-water intervals:
	 * it can therefore surface an UPSERT whose two retained membership fields were both corrupted.
	 * Candidate fact identities come from every provisionally related revision without first trusting
	 * its operation discriminator; an UPSERT changed into another shape therefore cannot disappear.
	 * Admission ordinals only discover corrupt candidates; they never establish ownership or counting
	 * authority. Equal/shared generations intentionally attach ambiguous invalid evidence to every
	 * plausible requested run. The query reports whether the scope carrier's raw pair names a durable
	 * run, but callers may use that relation only after authenticating the carrier checksum: a valid
	 * unrequested replacement sibling is then ignored, while a corrupt or nonexistent attribution
	 * remains attached for contextual failure. Keyset filtering, DISTINCT, and LIMIT apply to fact
	 * identities before that anchor fan-out, so callers can validate every candidate through bounded
	 * pages.
	 * Every persisted column is returned without constructing the validated entity, so malformed
	 * retained rows become typed integrity failures instead of Room mapping exceptions.
	 */
	@Query(
		"""
		WITH requested_run AS (
			SELECT service_run_id,
			       logical_tracking_id
			FROM source_service_run
			WHERE service_run_id IN (:serviceRunIds)
		), requested_generation AS (
			SELECT requested_run.service_run_id AS provisional_service_run_id,
			       completeness.registration_generation,
			       MAX(completeness.last_admission_ordinal) AS upper_admission_ordinal
			FROM requested_run
			JOIN source_session_completeness AS completeness
			  ON completeness.service_run_id = requested_run.service_run_id
			 AND completeness.logical_tracking_id = requested_run.logical_tracking_id
			WHERE completeness.source_kind = :sourceKind
			GROUP BY requested_run.service_run_id,
			         completeness.registration_generation
		), source_activation AS (
			SELECT MIN(lane.activation_ordinal) - 1 AS floor_admission_ordinal
			FROM source_product_projection_lane AS lane
			WHERE lane.source_kind = :sourceKind
		), bounded_generation AS (
			SELECT requested_generation.provisional_service_run_id,
			       requested_generation.registration_generation,
			       COALESCE(
			         (
			           SELECT MAX(prior.last_admission_ordinal)
			           FROM source_session_completeness AS prior
			           WHERE prior.source_kind = :sourceKind
			             AND prior.registration_generation <
			               requested_generation.registration_generation
			             AND prior.last_admission_ordinal <
			               requested_generation.upper_admission_ordinal
			         ),
			         (SELECT floor_admission_ordinal FROM source_activation),
			         0
			       ) AS lower_admission_ordinal,
			       requested_generation.upper_admission_ordinal
			FROM requested_generation
			WHERE requested_generation.upper_admission_ordinal IS NOT NULL
		), provisional_candidate AS (
			SELECT revision.writer_projection_id,
			       revision.writer_projection_version,
			       revision.logical_fact_id
			FROM step_fact_revision AS revision
			WHERE revision.service_run_id IN (:serviceRunIds)
			UNION
			SELECT revision.writer_projection_id,
			       revision.writer_projection_version,
			       revision.logical_fact_id
			FROM step_fact_revision AS revision
			WHERE revision.logical_tracking_id IN (
				SELECT logical_tracking_id
				FROM requested_run
			)
			UNION
			SELECT revision.writer_projection_id,
			       revision.writer_projection_version,
			       revision.logical_fact_id
			FROM step_fact_revision AS revision
			WHERE EXISTS (
				SELECT 1
				FROM bounded_generation
				WHERE revision.source_admission_ordinal >
				        bounded_generation.lower_admission_ordinal
				  AND revision.source_admission_ordinal <=
				        bounded_generation.upper_admission_ordinal
			)
		), candidate_fact AS (
			SELECT writer_projection_id,
			       writer_projection_version,
			       logical_fact_id
			FROM provisional_candidate
			WHERE :afterWriterProjectionId IS NULL
			   OR writer_projection_id > :afterWriterProjectionId
			   OR (
			     writer_projection_id = :afterWriterProjectionId
			     AND writer_projection_version > :afterWriterProjectionVersion
			   )
			   OR (
			     writer_projection_id = :afterWriterProjectionId
			     AND writer_projection_version = :afterWriterProjectionVersion
			     AND logical_fact_id > :afterLogicalFactId
			   )
			GROUP BY writer_projection_id,
			         writer_projection_version,
			         logical_fact_id
			ORDER BY writer_projection_id,
			         writer_projection_version,
			         logical_fact_id
			LIMIT :limit
		), scope_carrier AS (
			SELECT carrier.*
			FROM candidate_fact
			JOIN step_fact_revision AS carrier
			  ON carrier.writer_projection_id = candidate_fact.writer_projection_id
			 AND carrier.writer_projection_version = candidate_fact.writer_projection_version
			 AND carrier.logical_fact_id = candidate_fact.logical_fact_id
			WHERE carrier.semantic_revision = COALESCE(
			  (
			    SELECT MAX(upsert.semantic_revision)
			    FROM step_fact_revision AS upsert
			    WHERE upsert.writer_projection_id = candidate_fact.writer_projection_id
			      AND upsert.writer_projection_version = candidate_fact.writer_projection_version
			      AND upsert.logical_fact_id = candidate_fact.logical_fact_id
			      AND upsert.operation = 'UPSERT'
			  ),
			  (
			    SELECT MAX(fallback.semantic_revision)
			    FROM step_fact_revision AS fallback
			    WHERE fallback.writer_projection_id = candidate_fact.writer_projection_id
			      AND fallback.writer_projection_version = candidate_fact.writer_projection_version
			      AND fallback.logical_fact_id = candidate_fact.logical_fact_id
			  )
			)
		), candidate_anchor AS (
			SELECT revision.service_run_id AS provisional_service_run_id,
			       candidate_fact.writer_projection_id,
			       candidate_fact.writer_projection_version,
			       candidate_fact.logical_fact_id
			FROM candidate_fact
			JOIN step_fact_revision AS revision
			  ON revision.writer_projection_id = candidate_fact.writer_projection_id
			 AND revision.writer_projection_version = candidate_fact.writer_projection_version
			 AND revision.logical_fact_id = candidate_fact.logical_fact_id
			WHERE revision.service_run_id IN (:serviceRunIds)
			UNION
			SELECT requested_run.service_run_id AS provisional_service_run_id,
			       candidate_fact.writer_projection_id,
			       candidate_fact.writer_projection_version,
			       candidate_fact.logical_fact_id
			FROM candidate_fact
			JOIN step_fact_revision AS revision
			  ON revision.writer_projection_id = candidate_fact.writer_projection_id
			 AND revision.writer_projection_version = candidate_fact.writer_projection_version
			 AND revision.logical_fact_id = candidate_fact.logical_fact_id
			JOIN requested_run
			  ON requested_run.logical_tracking_id = revision.logical_tracking_id
			UNION
			SELECT bounded_generation.provisional_service_run_id,
			       candidate_fact.writer_projection_id,
			       candidate_fact.writer_projection_version,
			       candidate_fact.logical_fact_id
			FROM candidate_fact
			JOIN step_fact_revision AS revision
			  ON revision.writer_projection_id = candidate_fact.writer_projection_id
			 AND revision.writer_projection_version = candidate_fact.writer_projection_version
			 AND revision.logical_fact_id = candidate_fact.logical_fact_id
			JOIN bounded_generation
			  ON revision.source_admission_ordinal >
			       bounded_generation.lower_admission_ordinal
			 AND revision.source_admission_ordinal <=
			       bounded_generation.upper_admission_ordinal
		)
		SELECT candidate_anchor.provisional_service_run_id,
		       CASE WHEN EXISTS (
		         SELECT 1
		         FROM source_service_run AS attributed_run
		         WHERE attributed_run.service_run_id = scope_carrier.service_run_id
		           AND attributed_run.logical_tracking_id = scope_carrier.logical_tracking_id
		       ) THEN 1 ELSE 0 END AS has_durable_attributed_run,
		       (
		         SELECT COUNT(*)
		         FROM step_fact_revision AS invalid_revision
		         WHERE invalid_revision.writer_projection_id =
		                 candidate_anchor.writer_projection_id
		           AND invalid_revision.writer_projection_version =
		                 candidate_anchor.writer_projection_version
		           AND invalid_revision.logical_fact_id = candidate_anchor.logical_fact_id
		           AND invalid_revision.semantic_revision <= 0
		       ) AS invalid_semantic_revision_count,
		       scope_carrier.*,
		       state.logical_fact_id AS state_logical_fact_id,
		       state.semantic_revision AS state_semantic_revision,
		       state.mutation_id AS state_mutation_id,
		       state.step_interval_id AS state_step_interval_id,
		       state.source_event_id AS state_source_event_id,
		       state.source_admission_ordinal AS state_source_admission_ordinal,
		       state.origin_kind AS state_origin_kind,
		       state.origin_identity AS state_origin_identity,
		       state.writer_projection_id AS state_writer_projection_id,
		       state.writer_projection_version AS state_writer_projection_version,
		       state.writer_binding_generation AS state_writer_binding_generation,
		       state.operation AS state_operation,
		       state.interval_start_time_ms AS state_interval_start_time_ms,
		       state.interval_end_time_ms AS state_interval_end_time_ms,
		       state.interval_start_elapsed_realtime_nanos
		         AS state_interval_start_elapsed_realtime_nanos,
		       state.interval_end_elapsed_realtime_nanos
		         AS state_interval_end_elapsed_realtime_nanos,
		       state.clock_domain_id AS state_clock_domain_id,
		       state.boot_clock_domain_id AS state_boot_clock_domain_id,
		       state.cumulative_step_count_start AS state_cumulative_step_count_start,
		       state.cumulative_step_count_end AS state_cumulative_step_count_end,
		       state.wall_time_uncertainty_ms AS state_wall_time_uncertainty_ms,
		       state.coverage_kind AS state_coverage_kind,
		       state.effective_step_count AS state_effective_step_count,
		       state.logical_tracking_id AS state_logical_tracking_id,
		       state.service_run_id AS state_service_run_id,
		       state.purpose AS state_purpose,
		       state.manifest_revision AS state_manifest_revision,
		       state.source_policy_revision AS state_source_policy_revision,
		       state.capture_consent_epoch AS state_capture_consent_epoch,
		       state.collected_data_epoch AS state_collected_data_epoch,
		       state.scope_deletion_generation AS state_scope_deletion_generation,
		       state.effect_checksum AS state_effect_checksum,
		       state.applied_at_ms AS state_applied_at_ms
		FROM candidate_anchor
		JOIN scope_carrier
		  ON scope_carrier.writer_projection_id = candidate_anchor.writer_projection_id
		 AND scope_carrier.writer_projection_version =
		       candidate_anchor.writer_projection_version
		 AND scope_carrier.logical_fact_id = candidate_anchor.logical_fact_id
		JOIN step_fact_revision AS state
		  ON state.writer_projection_id = scope_carrier.writer_projection_id
		 AND state.writer_projection_version = scope_carrier.writer_projection_version
		 AND state.logical_fact_id = scope_carrier.logical_fact_id
		WHERE state.semantic_revision = (
			SELECT MAX(newer.semantic_revision)
			FROM step_fact_revision AS newer
			WHERE newer.writer_projection_id = scope_carrier.writer_projection_id
			  AND newer.writer_projection_version = scope_carrier.writer_projection_version
			  AND newer.logical_fact_id = scope_carrier.logical_fact_id
		)
		ORDER BY candidate_anchor.writer_projection_id,
		         candidate_anchor.writer_projection_version,
		         candidate_anchor.logical_fact_id,
		         candidate_anchor.provisional_service_run_id
		""",
	)
	suspend fun stepFactStates(
		serviceRunIds: List<String>,
		limit: Int,
		sourceKind: Int,
		afterWriterProjectionId: String? = null,
		afterWriterProjectionVersion: Long? = null,
		afterLogicalFactId: String? = null,
	): List<UnvalidatedStepFactState>

	/**
	 * Keyset page of fact-global latest states scoped through each fact's latest UPSERT attribution.
	 *
	 * Cursor keys must all be null for the first page or identify the final row from the preceding
	 * page. The UPSERT interval key remains available even when the latest state is a redacted
	 * RETRACT.
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
		       scoped_fact.first_interval_start_time_ms,
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
		  AND (
			:afterServiceRunId IS NULL
			OR scoped_fact.scoped_service_run_id > :afterServiceRunId
			OR (
			  scoped_fact.scoped_service_run_id = :afterServiceRunId
			  AND (
				scoped_fact.first_interval_start_time_ms >
					COALESCE(:afterFirstIntervalStartTimeMs, -1)
				OR (
				  scoped_fact.first_interval_start_time_ms =
					COALESCE(:afterFirstIntervalStartTimeMs, -1)
				  AND (
					scoped_fact.logical_fact_id > COALESCE(:afterLogicalFactId, '')
					OR (
					  scoped_fact.logical_fact_id = COALESCE(:afterLogicalFactId, '')
					  AND (
						scoped_fact.writer_projection_id > COALESCE(:afterWriterProjectionId, '')
						OR (
						  scoped_fact.writer_projection_id =
							COALESCE(:afterWriterProjectionId, '')
						  AND scoped_fact.writer_projection_version >
							COALESCE(:afterWriterProjectionVersion, -1)
						)
					  )
					)
				  )
				)
			  )
			)
		  )
		ORDER BY scoped_fact.scoped_service_run_id,
		         scoped_fact.first_interval_start_time_ms,
		         scoped_fact.logical_fact_id,
		         scoped_fact.writer_projection_id,
		         scoped_fact.writer_projection_version
		LIMIT :limit
		""",
	)
	suspend fun stepFactStatePage(
		serviceRunIds: List<String>,
		limit: Int,
		afterServiceRunId: String?,
		afterFirstIntervalStartTimeMs: Long?,
		afterLogicalFactId: String?,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
	): List<ScopedStepFactState>

	/**
	 * Keyset page of exact LIVE_WAL UPSERTs in source-admission order for run-timeline validation.
	 *
	 * Callers first validate the untrusted candidate relation; this typed read can then stream the
	 * canonical producer chain without retaining an unbounded day window in memory.
	 */
	@Query(
		"""
		SELECT * FROM step_fact_revision
		WHERE service_run_id IN (:serviceRunIds)
		  AND purpose = 'SESSION_CAPTURE'
		  AND operation = 'UPSERT'
		  AND origin_kind = 'LIVE_WAL'
		  AND source_admission_ordinal IS NOT NULL
		  AND (
			:afterServiceRunId IS NULL
			OR service_run_id > :afterServiceRunId
			OR (
			  service_run_id = :afterServiceRunId
			  AND (
				source_admission_ordinal > COALESCE(:afterSourceAdmissionOrdinal, -1)
				OR (
				  source_admission_ordinal = COALESCE(:afterSourceAdmissionOrdinal, -1)
				  AND (
					writer_projection_id > COALESCE(:afterWriterProjectionId, '')
					OR (
					  writer_projection_id = COALESCE(:afterWriterProjectionId, '')
					  AND (
						writer_projection_version > COALESCE(:afterWriterProjectionVersion, -1)
						OR (
						  writer_projection_version =
							COALESCE(:afterWriterProjectionVersion, -1)
						  AND (
							logical_fact_id > COALESCE(:afterLogicalFactId, '')
							OR (
							  logical_fact_id = COALESCE(:afterLogicalFactId, '')
							  AND semantic_revision >
								COALESCE(:afterSemanticRevision, -1)
							)
						  )
						)
					  )
					)
				  )
				)
			  )
			)
		  )
		ORDER BY service_run_id,
		         source_admission_ordinal,
		         writer_projection_id,
		         writer_projection_version,
		         logical_fact_id,
		         semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun canonicalStepFactTimelinePage(
		serviceRunIds: List<String>,
		limit: Int,
		afterServiceRunId: String?,
		afterSourceAdmissionOrdinal: Long?,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
	): List<StepFactRevisionEntity>

	/**
	 * Keyset page of all historical session-capture UPSERT revisions for exact service runs.
	 *
	 * Portable export needs the correction-expanded dependency count even though only the latest
	 * effective state is product-visible. RETRACT rows are intentionally excluded because they are
	 * redacted and are already represented by the latest-state query.
	 */
	@Query(
		"""
		SELECT * FROM step_fact_revision
		WHERE service_run_id IN (:serviceRunIds)
		  AND purpose = :capturePurpose
		  AND operation = 'UPSERT'
		  AND (
			:afterServiceRunId IS NULL
			OR service_run_id > :afterServiceRunId
			OR (
			  service_run_id = :afterServiceRunId
			  AND (
				writer_projection_id > COALESCE(:afterWriterProjectionId, '')
				OR (
				  writer_projection_id = COALESCE(:afterWriterProjectionId, '')
				  AND (
					writer_projection_version > COALESCE(:afterWriterProjectionVersion, -1)
					OR (
					  writer_projection_version = COALESCE(:afterWriterProjectionVersion, -1)
					  AND (
						logical_fact_id > COALESCE(:afterLogicalFactId, '')
						OR (
						  logical_fact_id = COALESCE(:afterLogicalFactId, '')
						  AND semantic_revision > COALESCE(:afterSemanticRevision, -1)
						)
					  )
					)
				  )
				)
			  )
			)
		  )
		ORDER BY service_run_id,
		         writer_projection_id,
		         writer_projection_version,
		         logical_fact_id,
		         semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun stepFactUpsertRevisionPage(
		serviceRunIds: List<String>,
		capturePurpose: String,
		limit: Int,
		afterServiceRunId: String?,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
	): List<StepFactRevisionEntity>

	/**
	 * Keyset page of each complete fact-global lineage selected by exact writer and fact identity.
	 *
	 * Portable export first discovers bounded run-local UPSERT seeds, then uses this query to expose
	 * corrections and retractions even when a later revision changes service-run attribution. The
	 * caller validates the full lineage before emitting any portable value.
	 */
	@Query(
		"""
		SELECT * FROM step_fact_revision
		WHERE writer_projection_id = :writerProjectionId
		  AND writer_projection_version = :writerProjectionVersion
		  AND logical_fact_id IN (:logicalFactIds)
		  AND (
			:afterLogicalFactId IS NULL
			OR logical_fact_id > :afterLogicalFactId
			OR (
			  logical_fact_id = :afterLogicalFactId
			  AND semantic_revision > COALESCE(:afterSemanticRevision, -1)
			)
		  )
		ORDER BY logical_fact_id, semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun portableStepFactLineagePage(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
		limit: Int,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
	): List<StepFactRevisionEntity>

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
	@ColumnInfo(name = "first_interval_start_time_ms")
	val firstIntervalStartTimeMs: Long,
	@Embedded
	val state: StepFactRevisionEntity,
)

/** Untrusted complete UPSERT carrier paired with its untrusted fact-global latest state. */
data class UnvalidatedStepFactState(
	@ColumnInfo(name = "provisional_service_run_id")
	val provisionalServiceRunId: String,
	@ColumnInfo(name = "has_durable_attributed_run")
	val hasDurableAttributedRun: Boolean,
	@ColumnInfo(name = "invalid_semantic_revision_count")
	val invalidSemanticRevisionCount: Int,
	@Embedded
	val scopeCarrier: UnvalidatedStepFactRevision,
	@Embedded(prefix = "state_")
	val state: UnvalidatedStepFactRevision,
)

/**
 * Complete persisted Steps-fact shape without constructor invariants.
 *
 * History reads deliberately map through this type: corruption must become a typed product result,
 * not an exception raised while Room constructs [StepFactRevisionEntity].
 */
@Suppress("LongParameterList")
data class UnvalidatedStepFactRevision(
	@ColumnInfo(name = "logical_fact_id") val logicalFactId: String,
	@ColumnInfo(name = "semantic_revision") val semanticRevision: Long,
	@ColumnInfo(name = "mutation_id") val mutationId: String,
	@ColumnInfo(name = "step_interval_id") val stepIntervalId: Long?,
	@ColumnInfo(name = "source_event_id") val sourceEventId: String?,
	@ColumnInfo(name = "source_admission_ordinal") val sourceAdmissionOrdinal: Long?,
	@ColumnInfo(name = "origin_kind") val originKind: String,
	@ColumnInfo(name = "origin_identity") val originIdentity: String,
	@ColumnInfo(name = "writer_projection_id") val writerProjectionId: String,
	@ColumnInfo(name = "writer_projection_version") val writerProjectionVersion: Long,
	@ColumnInfo(name = "writer_binding_generation") val writerBindingGeneration: Long,
	@ColumnInfo(name = "operation") val operation: String,
	@ColumnInfo(name = "interval_start_time_ms") val intervalStartTimeMs: Long?,
	@ColumnInfo(name = "interval_end_time_ms") val intervalEndTimeMs: Long?,
	@ColumnInfo(name = "interval_start_elapsed_realtime_nanos")
	val intervalStartElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "interval_end_elapsed_realtime_nanos")
	val intervalEndElapsedRealtimeNanos: Long?,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String?,
	@ColumnInfo(name = "boot_clock_domain_id") val bootClockDomainId: String?,
	@ColumnInfo(name = "cumulative_step_count_start") val cumulativeStepCountStart: Long?,
	@ColumnInfo(name = "cumulative_step_count_end") val cumulativeStepCountEnd: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "coverage_kind") val coverageKind: String?,
	@ColumnInfo(name = "effective_step_count") val effectiveStepCount: Long?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "purpose") val purpose: String,
	@ColumnInfo(name = "manifest_revision") val manifestRevision: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "collected_data_epoch") val collectedDataEpoch: Long,
	@ColumnInfo(name = "scope_deletion_generation") val scopeDeletionGeneration: Long,
	@ColumnInfo(name = "effect_checksum") val effectChecksum: String,
	@ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
) {
	/** Applies every production entity invariant without allowing malformed rows to escape the read. */
	fun validatedOrNull(): StepFactRevisionEntity? {
		if (writerProjectionVersion !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
			return null
		}
		return try {
			StepFactRevisionEntity(
				logicalFactId = logicalFactId,
				semanticRevision = semanticRevision,
				mutationId = mutationId,
				stepIntervalId = stepIntervalId,
				sourceEventId = sourceEventId,
				sourceAdmissionOrdinal = sourceAdmissionOrdinal,
				originKind = originKind,
				originIdentity = originIdentity,
				writerProjectionId = writerProjectionId,
				writerProjectionVersion = writerProjectionVersion.toInt(),
				writerBindingGeneration = writerBindingGeneration,
				operation = operation,
				intervalStartTimeMs = intervalStartTimeMs,
				intervalEndTimeMs = intervalEndTimeMs,
				intervalStartElapsedRealtimeNanos = intervalStartElapsedRealtimeNanos,
				intervalEndElapsedRealtimeNanos = intervalEndElapsedRealtimeNanos,
				clockDomainId = clockDomainId,
				bootClockDomainId = bootClockDomainId,
				cumulativeStepCountStart = cumulativeStepCountStart,
				cumulativeStepCountEnd = cumulativeStepCountEnd,
				wallTimeUncertaintyMs = wallTimeUncertaintyMs,
				coverageKind = coverageKind,
				effectiveStepCount = effectiveStepCount,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				purpose = purpose,
				manifestRevision = manifestRevision,
				sourcePolicyRevision = sourcePolicyRevision,
				captureConsentEpoch = captureConsentEpoch,
				collectedDataEpoch = collectedDataEpoch,
				scopeDeletionGeneration = scopeDeletionGeneration,
				effectChecksum = effectChecksum,
				appliedAtMs = appliedAtMs,
			)
		} catch (_: IllegalArgumentException) {
			null
		}
	}
}
