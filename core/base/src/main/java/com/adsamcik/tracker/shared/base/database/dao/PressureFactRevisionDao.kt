package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose

/** Deliberately narrow storage boundary for the dormant Pressure fact writer. */
@Dao
interface PressureFactRevisionDao {
	/** Appends one immutable revision, returning its row id or `-1` for an existing identity. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(entity: PressureFactRevisionEntity): Long

	/** Returns a row only when every replay identity resolves to that exact stored revision. */
	@Query(
		"SELECT * FROM pressure_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND semantic_revision = :semanticRevision " +
			"AND mutation_id = :mutationId AND source_admission_ordinal = :sourceAdmissionOrdinal " +
			"LIMIT 1",
	)
	suspend fun replay(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
		mutationId: String,
		sourceAdmissionOrdinal: Long,
	): PressureFactRevisionEntity?

	@Query(
		"SELECT * FROM pressure_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId ORDER BY semantic_revision DESC LIMIT 1",
	)
	/** Returns the latest semantic revision for one exact Pressure writer-owned fact. */
	suspend fun latest(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): PressureFactRevisionEntity?

	/** Returns the first deterministic page attributed to one exact run scope. */
	@Query(
		"SELECT * FROM pressure_fact_revision WHERE service_run_id = :serviceRunId " +
			"AND logical_tracking_id = :logicalTrackingId " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, " +
			"semantic_revision LIMIT :limit",
	)
	suspend fun firstExactServiceRunPage(
		logicalTrackingId: String,
		serviceRunId: String,
		limit: Int,
	): List<PressureFactRevisionEntity>

	/** Seeks after an exact primary-key cursor without offset or per-row fan-out. */
	@Query(
		"SELECT * FROM pressure_fact_revision WHERE service_run_id = :serviceRunId " +
			"AND logical_tracking_id = :logicalTrackingId " +
			"AND (writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision) " +
			"> (:afterWriterId, :afterWriterVersion, :afterLogicalFactId, :afterSemanticRevision) " +
			"ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, " +
			"semantic_revision LIMIT :limit",
	)
	suspend fun exactServiceRunPageAfter(
		logicalTrackingId: String,
		serviceRunId: String,
		afterWriterId: String,
		afterWriterVersion: Int,
		afterLogicalFactId: String,
		afterSemanticRevision: Long,
		limit: Int,
	): List<PressureFactRevisionEntity>

	/**
	 * Discovers presentation rows from retained Pressure facts and exact reciprocal run binding.
	 *
	 * A segment's generic sample count is deliberately absent. This is only a coarse source-local
	 * seed; the Pressure history selector still expands the complete logical replacement membership
	 * and authenticates every manifest, policy, consent, writer, fact, and lifecycle row.
	 */
	@Query(
		"""
		SELECT segment.*
		FROM session_segment AS segment
		WHERE EXISTS (
		  SELECT 1
		  FROM source_service_run AS run
		  JOIN pressure_fact_revision AS fact
		    ON fact.service_run_id = run.service_run_id
		   AND fact.logical_tracking_id = run.logical_tracking_id
		  WHERE run.session_segment_id = segment.id
		    AND run.service_run_id = segment.service_run_id
		    AND run.logical_tracking_id = segment.logical_tracking_id
		    AND fact.purpose = '${PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE}'
		)
		  AND (
		    :beforeStartTimeMs IS NULL
		    OR segment.start_time_ms < :beforeStartTimeMs
		    OR (
		      segment.start_time_ms = :beforeStartTimeMs
		      AND segment.id < COALESCE(:beforeSegmentId, 9223372036854775807)
		    )
		  )
		ORDER BY segment.start_time_ms DESC, segment.id DESC
		LIMIT :limit
		""",
	)
	suspend fun historySegmentCandidatePage(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeSegmentId: Long?,
	): List<SessionSegment>

	/**
	 * Discovers one Pressure-evidence candidate per logical entry in exact entry recency order.
	 *
	 * A retained fact is direct evidence. Exact Pressure capture manifest membership is only a coarse
	 * seed for a possible payload-free retention marker, whose opaque scope digest cannot be joined
	 * in SQLite. The product reader expands the complete replacement group and authenticates the
	 * marker against the current collected-data epoch before accepting it. Generic sample counts are
	 * deliberately absent.
	 */
	@Query(
		"""
		WITH pressure_evidence_member AS (
		  SELECT segment.*
		  FROM session_segment AS segment
		  INNER JOIN source_service_run AS run
		    ON run.session_segment_id = segment.id
		   AND run.service_run_id = segment.service_run_id
		   AND run.logical_tracking_id = segment.logical_tracking_id
		  WHERE (
		    EXISTS (
		      SELECT 1
		      FROM pressure_fact_revision AS fact
		      WHERE fact.service_run_id = run.service_run_id
		        AND fact.logical_tracking_id = run.logical_tracking_id
		        AND fact.purpose = '${PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE}'
		    )
		    OR EXISTS (
		      SELECT 1
		      FROM session_manifest_version AS manifest
		      INNER JOIN session_manifest_source AS source
		        ON source.logical_tracking_id = manifest.logical_tracking_id
		       AND source.manifest_revision = manifest.manifest_revision
		      WHERE manifest.service_run_id = run.service_run_id
		        AND manifest.logical_tracking_id = run.logical_tracking_id
		        AND source.source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE}
		        AND source.purpose = '${SessionManifestPurposeCode.SESSION_CAPTURE}'
		        AND source.persistence_eligible = 1
		    )
		  )
		), pressure_evidence_seed AS (
		  SELECT member.*
		  FROM pressure_evidence_member AS member
		  WHERE NOT EXISTS (
		      SELECT 1
		      FROM pressure_evidence_member AS newer
		      WHERE newer.logical_tracking_id = member.logical_tracking_id
		        AND (
		          newer.start_time_ms > member.start_time_ms
		          OR (
		            newer.start_time_ms = member.start_time_ms
		            AND newer.id > member.id
		          )
		        )
		    )
		), logical_ranked_seed AS (
		  SELECT seed.*,
		    (
		      SELECT member_segment.start_time_ms
		      FROM source_service_run AS member_run
		      INNER JOIN session_segment AS member_segment
		        ON member_segment.id = member_run.session_segment_id
		       AND member_segment.service_run_id = member_run.service_run_id
		       AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		      WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		      ORDER BY member_segment.start_time_ms DESC, member_segment.id DESC
		      LIMIT 1
		    ) AS logical_recency_start_ms,
		    (
		      SELECT member_segment.id
		      FROM source_service_run AS member_run
		      INNER JOIN session_segment AS member_segment
		        ON member_segment.id = member_run.session_segment_id
		       AND member_segment.service_run_id = member_run.service_run_id
		       AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		      WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		      ORDER BY member_segment.start_time_ms DESC, member_segment.id DESC
		      LIMIT 1
		    ) AS logical_recency_segment_id
		  FROM pressure_evidence_seed AS seed
		)
		SELECT *
		FROM logical_ranked_seed
		WHERE (
		  :beforeLogicalRecencyStartMs IS NULL
		  OR logical_recency_start_ms < :beforeLogicalRecencyStartMs
		  OR (
		    logical_recency_start_ms = :beforeLogicalRecencyStartMs
		    AND logical_recency_segment_id < COALESCE(
		      :beforeLogicalRecencySegmentId,
		      9223372036854775807
		    )
		  )
		)
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun pressureLogicalHistoryCandidatePage(
		limit: Int,
		beforeLogicalRecencyStartMs: Long?,
		beforeLogicalRecencySegmentId: Long?,
	): List<PressureLogicalHistoryCandidate>

	/**
	 * Pages one coarse Pressure-evidence seed for each logical entry overlapping an export range.
	 *
	 * The overlap predicate is evaluated against every reciprocally bound physical member, while the
	 * seed may come from any member carrying a retained fact or immutable Pressure capture manifest.
	 * The stats reader must still expand and authenticate the whole replacement group and any opaque
	 * retention marker. Generic segment sample counts are deliberately absent.
	 */
	@Query(
		"""
		WITH overlapping_logical AS (
		  SELECT DISTINCT member_run.logical_tracking_id
		  FROM source_service_run AS member_run
		  INNER JOIN session_segment AS member_segment
		    ON member_segment.id = member_run.session_segment_id
		   AND member_segment.service_run_id = member_run.service_run_id
		   AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		  WHERE member_segment.start_time_ms < :toExclusiveMs
		    AND member_segment.end_time_ms > :fromInclusiveMs
		), pressure_evidence_member AS (
		  SELECT segment.*
		  FROM session_segment AS segment
		  INNER JOIN source_service_run AS run
		    ON run.session_segment_id = segment.id
		   AND run.service_run_id = segment.service_run_id
		   AND run.logical_tracking_id = segment.logical_tracking_id
		  INNER JOIN overlapping_logical AS overlap
		    ON overlap.logical_tracking_id = run.logical_tracking_id
		  WHERE (
		    EXISTS (
		      SELECT 1
		      FROM pressure_fact_revision AS fact
		      WHERE fact.service_run_id = run.service_run_id
		        AND fact.logical_tracking_id = run.logical_tracking_id
		        AND fact.purpose = '${PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE}'
		    )
		    OR EXISTS (
		      SELECT 1
		      FROM session_manifest_version AS manifest
		      INNER JOIN session_manifest_source AS source
		        ON source.logical_tracking_id = manifest.logical_tracking_id
		       AND source.manifest_revision = manifest.manifest_revision
		      WHERE manifest.service_run_id = run.service_run_id
		        AND manifest.logical_tracking_id = run.logical_tracking_id
		        AND source.source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE}
		        AND source.purpose = '${SessionManifestPurposeCode.SESSION_CAPTURE}'
		        AND source.persistence_eligible = 1
		    )
		  )
		), pressure_evidence_seed AS (
		  SELECT member.*
		  FROM pressure_evidence_member AS member
		  WHERE NOT EXISTS (
		    SELECT 1
		    FROM pressure_evidence_member AS newer
		    WHERE newer.logical_tracking_id = member.logical_tracking_id
		      AND (
		        newer.start_time_ms > member.start_time_ms
		        OR (newer.start_time_ms = member.start_time_ms AND newer.id > member.id)
		      )
		  )
		), logical_ranked_seed AS (
		  SELECT seed.*,
		    (
		      SELECT member_segment.start_time_ms
		      FROM source_service_run AS member_run
		      INNER JOIN session_segment AS member_segment
		        ON member_segment.id = member_run.session_segment_id
		       AND member_segment.service_run_id = member_run.service_run_id
		       AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		      WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		      ORDER BY member_segment.start_time_ms DESC, member_segment.id DESC
		      LIMIT 1
		    ) AS logical_recency_start_ms,
		    (
		      SELECT member_segment.id
		      FROM source_service_run AS member_run
		      INNER JOIN session_segment AS member_segment
		        ON member_segment.id = member_run.session_segment_id
		       AND member_segment.service_run_id = member_run.service_run_id
		       AND member_segment.logical_tracking_id = member_run.logical_tracking_id
		      WHERE member_run.logical_tracking_id = seed.logical_tracking_id
		      ORDER BY member_segment.start_time_ms DESC, member_segment.id DESC
		      LIMIT 1
		    ) AS logical_recency_segment_id
		  FROM pressure_evidence_seed AS seed
		)
		SELECT *
		FROM logical_ranked_seed
		WHERE (
		  :beforeLogicalRecencyStartMs IS NULL
		  OR logical_recency_start_ms < :beforeLogicalRecencyStartMs
		  OR (
		    logical_recency_start_ms = :beforeLogicalRecencyStartMs
		    AND logical_recency_segment_id < COALESCE(
		      :beforeLogicalRecencySegmentId,
		      9223372036854775807
		    )
		  )
		)
		ORDER BY logical_recency_start_ms DESC, logical_recency_segment_id DESC
		LIMIT :limit
		""",
	)
	suspend fun portablePressureLogicalHistoryCandidatePage(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeLogicalRecencyStartMs: Long?,
		beforeLogicalRecencySegmentId: Long?,
	): List<PressureLogicalHistoryCandidate>

	/**
	 * Reads a bounded correction-expanded candidate page for exact physical/logical scopes.
	 *
	 * Product history validates every retained revision before selecting the effective state. The
	 * logical-id arm exposes a whole lineage moved away from its expected service run. The
	 * correlated arm then retains every revision of a lineage having any candidate-scoped member, so
	 * a correction that corrupts both ownership columns cannot hide. The full primary key cursor
	 * prevents a replacement run or correction boundary from being skipped without per-row queries.
	 */
	@Query(
		"""
		SELECT * FROM pressure_fact_revision
		WHERE (
		  service_run_id IN (:serviceRunIds)
		  OR logical_tracking_id IN (:logicalTrackingIds)
		  OR EXISTS (
		     SELECT 1
		     FROM pressure_fact_revision AS candidate
		     WHERE candidate.writer_projection_id = pressure_fact_revision.writer_projection_id
		       AND candidate.writer_projection_version = pressure_fact_revision.writer_projection_version
		       AND candidate.logical_fact_id = pressure_fact_revision.logical_fact_id
		       AND (
		         candidate.service_run_id IN (:serviceRunIds)
		         OR candidate.logical_tracking_id IN (:logicalTrackingIds)
		       )
		  )
		)
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
	suspend fun historyRevisionPage(
		serviceRunIds: List<String>,
		logicalTrackingIds: List<String>,
		limit: Int,
		afterServiceRunId: String?,
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Int?,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
	): List<PressureFactRevisionEntity>

	/** True when any requested run owns a fact lineage whose revisions escape that exact scope. */
	@Query(
		"""
		SELECT EXISTS(
		  SELECT 1
		  FROM pressure_fact_revision AS selected
		  INNER JOIN pressure_fact_revision AS correction
		    ON correction.writer_projection_id = selected.writer_projection_id
		   AND correction.writer_projection_version = selected.writer_projection_version
		   AND correction.logical_fact_id = selected.logical_fact_id
		  WHERE selected.service_run_id IN (:serviceRunIds)
		    AND (
		      correction.logical_tracking_id != selected.logical_tracking_id
		      OR correction.service_run_id != selected.service_run_id
		    )
		  LIMIT 1
		)
		""",
	)
	suspend fun hasCrossScopeRevisionsForRuns(serviceRunIds: List<String>): Boolean

	/** Detects rows that name the selected service run but contradict its logical-session owner. */
	@Query(
		"SELECT (EXISTS(SELECT 1 FROM pressure_fact_revision " +
			"WHERE service_run_id = :serviceRunId AND logical_tracking_id < :logicalTrackingId LIMIT 1) " +
			"OR EXISTS(SELECT 1 FROM pressure_fact_revision " +
			"WHERE service_run_id = :serviceRunId AND logical_tracking_id > :logicalTrackingId LIMIT 1))",
	)
	suspend fun hasServiceRunScopeMismatch(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	/**
	 * True when a fact identity selected through this exact scope has any revision outside it.
	 *
	 * The correlated lookup avoids an unbounded identity list while proving that corrections cannot
	 * escape the logical-session/service-run deletion boundary.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM pressure_fact_revision AS selected " +
			"INNER JOIN pressure_fact_revision AS correction " +
			"ON correction.writer_projection_id = selected.writer_projection_id " +
			"AND correction.writer_projection_version = selected.writer_projection_version " +
			"AND correction.logical_fact_id = selected.logical_fact_id " +
			"WHERE selected.logical_tracking_id = :logicalTrackingId " +
			"AND selected.service_run_id = :serviceRunId " +
			"AND (correction.logical_tracking_id != :logicalTrackingId " +
			"OR correction.service_run_id != :serviceRunId) LIMIT 1)",
	)
	suspend fun hasCrossScopeRevisions(
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean

	/** Pages every retained run id before retention trusts any checksummed fact field. */
	@Query(
		"SELECT DISTINCT service_run_id FROM pressure_fact_revision " +
			"WHERE (:afterServiceRunId IS NULL OR service_run_id > :afterServiceRunId) " +
			"ORDER BY service_run_id LIMIT :limit",
	)
	suspend fun retentionServiceRunIdPage(
		afterServiceRunId: String?,
		limit: Int,
	): List<String>

	/** Deletes every authenticated revision for the selected exact logical fact identities. */
	@Query(
		"DELETE FROM pressure_fact_revision WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId " +
			"AND writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id IN (:logicalFactIds)",
	)
	suspend fun deleteExactLineages(
		logicalTrackingId: String,
		serviceRunId: String,
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactIds: List<String>,
	): Int

	/** Deletes one validated primary-key page through its exact final cursor. */
	@Query(
		"DELETE FROM pressure_fact_revision WHERE logical_tracking_id = :logicalTrackingId " +
			"AND service_run_id = :serviceRunId AND writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND (logical_fact_id, semantic_revision) " +
			"<= (:throughLogicalFactId, :throughSemanticRevision)",
	)
	suspend fun deleteExactServiceRunPageThrough(
		logicalTrackingId: String,
		serviceRunId: String,
		writerProjectionId: String,
		writerProjectionVersion: Int,
		throughLogicalFactId: String,
		throughSemanticRevision: Long,
	): Int

	/** Counts all retained Pressure fact revisions across writer-owned session scopes. */
	@Query("SELECT COUNT(*) FROM pressure_fact_revision")
	suspend fun count(): Long

	/** Bounded ordered raw Pressure WAL page for source-wide privacy authentication. */
	@Query(
		"SELECT * FROM source_event_wal " +
			"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
			"AND (authorization_purpose_eligibility_mask & " +
			"${SourceBrokerPurpose.MASK_SESSION_CAPTURE}) != 0 " +
			"AND admission_ordinal > :afterAdmissionOrdinal " +
			"AND LENGTH(payload) <= :maximumPayloadBytes " +
			"ORDER BY admission_ordinal LIMIT :limit",
	)
	suspend fun sourceEraseWalPage(
		afterAdmissionOrdinal: Long,
		maximumPayloadBytes: Int,
		limit: Int,
	): List<SourceEventWalEntity>

	@Query(
		"SELECT COUNT(*) FROM source_event_wal " +
			"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
			"AND (authorization_purpose_eligibility_mask & " +
			"${SourceBrokerPurpose.MASK_SESSION_CAPTURE}) != 0",
	)
	suspend fun sourceEraseWalCount(): Long

	@Query(
		"DELETE FROM source_event_wal " +
			"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
			"AND (authorization_purpose_eligibility_mask & " +
			"${SourceBrokerPurpose.MASK_SESSION_CAPTURE}) != 0",
	)
	suspend fun deleteSourceEraseWal(): Int

	@Query("DELETE FROM pressure_fact_revision")
	suspend fun deleteFactsForSourceErase(): Int

	@Query("SELECT COUNT(*) FROM pressure_sample")
	suspend fun legacyPressureSampleCount(): Long

	@Query("SELECT MAX(time_ms) FROM pressure_sample")
	suspend fun latestLegacyPressureSampleTimeMs(): Long?

	@Query(
		"SELECT * FROM pressure_sample WHERE id > :afterId ORDER BY id LIMIT :limit",
	)
	suspend fun legacyPressureSampleErasePage(
		afterId: Long,
		limit: Int,
	): List<PressureSample>

	@Query("DELETE FROM pressure_sample")
	suspend fun deleteLegacyPressureSamplesForSourceErase(): Int

	@Query(
		"SELECT * FROM source_deletion_fence " +
			"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
			"AND purpose = '${SessionManifestPurposeCode.SESSION_CAPTURE}' " +
			"AND scope_kind = '${SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN}' " +
			"AND (:afterDigest IS NULL OR scope_identity_digest > :afterDigest) " +
			"ORDER BY scope_identity_digest LIMIT :limit",
	)
	suspend fun sourceEraseFencePage(
		afterDigest: String?,
		limit: Int,
	): List<SourceDeletionFenceEntity>

	@Query(
		"SELECT COUNT(*) FROM source_deletion_fence " +
			"WHERE source_kind = ${SourceDestinationOwnerEntity.SOURCE_PRESSURE} " +
			"AND purpose = '${SessionManifestPurposeCode.SESSION_CAPTURE}' " +
			"AND scope_kind = '${SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN}'",
	)
	suspend fun sourceEraseFenceCount(): Long

	/** Full collected-data clear only; no scoped Pressure deletion API is authorized here. */
	@Query("DELETE FROM pressure_fact_revision")
	fun deleteAll()
}


/** Fact-bearing seed plus the recency of its complete reciprocal logical membership. */
data class PressureLogicalHistoryCandidate(
	@Embedded
	val segment: SessionSegment,
	@ColumnInfo(name = "logical_recency_start_ms")
	val logicalRecencyStartMs: Long,
	@ColumnInfo(name = "logical_recency_segment_id")
	val logicalRecencySegmentId: Long,
)
