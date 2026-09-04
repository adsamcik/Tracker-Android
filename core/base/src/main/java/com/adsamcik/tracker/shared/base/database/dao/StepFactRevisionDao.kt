package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity

@Dao
interface StepFactRevisionDao {
	/** Returns -1 when this fact revision, mutation, or live writer ordinal already exists. */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(entity: StepFactRevisionEntity): Long

	@Query(
		"SELECT * FROM step_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId AND semantic_revision = :semanticRevision",
	)
	suspend fun revision(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
		semanticRevision: Long,
	): StepFactRevisionEntity?

	@Query(
		"SELECT * FROM step_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND mutation_id = :mutationId",
	)
	suspend fun mutation(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		mutationId: String,
	): StepFactRevisionEntity?

	/** Exact one-delivery receipt lookup used to distinguish replay from identity conflict. */
	@Query(
		"SELECT * FROM step_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND source_admission_ordinal = :sourceAdmissionOrdinal",
	)
	suspend fun writerAdmission(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		sourceAdmissionOrdinal: Long,
	): StepFactRevisionEntity?

	@Query(
		"SELECT * FROM step_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT 1",
	)
	suspend fun latest(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): StepFactRevisionEntity?

	@Query(
		"SELECT * FROM step_fact_revision WHERE writer_projection_id = :writerProjectionId " +
			"AND writer_projection_version = :writerProjectionVersion " +
			"AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision ASC",
	)
	suspend fun revisions(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalFactId: String,
	): List<StepFactRevisionEntity>

	/** Latest UPSERTs whose self-contained immutable intervals overlap the requested wall range. */
	@Query(
		"""
		SELECT revision.* FROM step_fact_revision AS revision
		WHERE revision.writer_projection_id = :writerProjectionId
		  AND revision.writer_projection_version = :writerProjectionVersion
		  AND revision.logical_tracking_id = :logicalTrackingId
		  AND revision.purpose = 'SESSION_CAPTURE'
		  AND revision.operation = 'UPSERT'
		  AND revision.interval_end_time_ms >= :fromMs
		  AND revision.interval_start_time_ms <= :toMs
		  AND NOT EXISTS (
			SELECT 1 FROM step_fact_revision AS newer
			WHERE newer.writer_projection_id = :writerProjectionId
			  AND newer.writer_projection_version = :writerProjectionVersion
			  AND newer.logical_fact_id = revision.logical_fact_id
			  AND newer.semantic_revision > revision.semantic_revision
		  )
		ORDER BY revision.interval_start_time_ms ASC, revision.logical_fact_id ASC
		""",
	)
	suspend fun latestEffectiveBetween(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalTrackingId: String,
		fromMs: Long,
		toMs: Long,
	): List<StepFactRevisionEntity>

	/**
	 * Latest states for facts whose latest UPSERT belongs to one immutable service-run binding.
	 *
	 * Membership follows the fact's latest UPSERT so a correction that changes attribution cannot be
	 * counted in both its old and new service runs. The outer query then resolves the fact-global
	 * latest state, allowing a later redacted RETRACT to inherit only that latest UPSERT's scope even
	 * though deletion deliberately clears its session and interval columns.
	 */
	@Query(
		"""
		WITH latest_upsert AS (
			SELECT upsert.* FROM step_fact_revision AS upsert
			WHERE upsert.writer_projection_id = :writerProjectionId
			  AND upsert.writer_projection_version = :writerProjectionVersion
			  AND upsert.operation = 'UPSERT'
			  AND NOT EXISTS (
				SELECT 1 FROM step_fact_revision AS newer_upsert
				WHERE newer_upsert.writer_projection_id = :writerProjectionId
				  AND newer_upsert.writer_projection_version = :writerProjectionVersion
				  AND newer_upsert.logical_fact_id = upsert.logical_fact_id
				  AND newer_upsert.operation = 'UPSERT'
				  AND newer_upsert.semantic_revision > upsert.semantic_revision
			  )
		), scoped_fact AS (
			SELECT latest_upsert.logical_fact_id,
			       latest_upsert.interval_start_time_ms AS first_interval_start_time_ms
			FROM latest_upsert
			WHERE latest_upsert.writer_binding_generation = :writerBindingGeneration
			  AND latest_upsert.logical_tracking_id = :logicalTrackingId
			  AND latest_upsert.service_run_id = :serviceRunId
			  AND latest_upsert.manifest_revision IN (:manifestRevisions)
			  AND latest_upsert.purpose = 'SESSION_CAPTURE'
		)
		SELECT state.* FROM scoped_fact
		JOIN step_fact_revision AS state
		  ON state.writer_projection_id = :writerProjectionId
		 AND state.writer_projection_version = :writerProjectionVersion
		 AND state.logical_fact_id = scoped_fact.logical_fact_id
		WHERE state.semantic_revision = (
			SELECT MAX(newer.semantic_revision)
			FROM step_fact_revision AS newer
			WHERE newer.writer_projection_id = :writerProjectionId
			  AND newer.writer_projection_version = :writerProjectionVersion
			  AND newer.logical_fact_id = scoped_fact.logical_fact_id
		)
		ORDER BY scoped_fact.first_interval_start_time_ms ASC, state.logical_fact_id ASC
		LIMIT :limit
		""",
	)
	suspend fun latestStatesForServiceRun(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		writerBindingGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevisions: List<Long>,
		limit: Int = Int.MAX_VALUE,
	): List<StepFactRevisionEntity>

	/**
	 * Every payload-bearing revision attributed to one exact logical/service-run scope.
	 *
	 * Selected-session deletion validates immutable manifest, policy, consent, and calendar
	 * attribution for every row it will remove. This deliberately spans writer versions and old
	 * corrections so malformed historical payload cannot be hidden by a newer latest state.
	 */
	@Query(
		"""
		SELECT * FROM step_fact_revision
		WHERE operation = 'UPSERT'
		  AND logical_tracking_id = :logicalTrackingId
		  AND service_run_id = :serviceRunId
		  AND purpose = 'SESSION_CAPTURE'
		ORDER BY writer_projection_id, writer_projection_version, logical_fact_id, semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun upsertsForServiceRun(
		logicalTrackingId: String,
		serviceRunId: String,
		limit: Int = Int.MAX_VALUE,
	): List<StepFactRevisionEntity>

	/**
	 * Removes every payload-bearing revision attributed to one exact Steps run.
	 *
	 * The selected-session command inserts redacted latest-state retractions and the permanent scope
	 * fence before calling this query in the same transaction. Deliberately do not filter writer or
	 * manifest revision here: deletion is permanent across writer upgrades, corrections, and imports,
	 * and exact logical/run identity is stronger than historical writer membership.
	 */
	@Query(
		"""
		DELETE FROM step_fact_revision
		WHERE operation = 'UPSERT'
		  AND logical_tracking_id = :logicalTrackingId
		  AND service_run_id = :serviceRunId
		  AND purpose = 'SESSION_CAPTURE'
		""",
	)
	suspend fun deleteUpsertsForServiceRun(
		logicalTrackingId: String,
		serviceRunId: String,
	): Int

	@Query(
		"""
		SELECT SUM(revision.effective_step_count)
		FROM step_fact_revision AS revision
		WHERE revision.writer_projection_id = :writerProjectionId
		  AND revision.writer_projection_version = :writerProjectionVersion
		  AND revision.logical_tracking_id = :logicalTrackingId
		  AND revision.purpose = 'SESSION_CAPTURE'
		  AND revision.operation = 'UPSERT'
		  AND NOT EXISTS (
			SELECT 1 FROM step_fact_revision AS newer
			WHERE newer.writer_projection_id = :writerProjectionId
			  AND newer.writer_projection_version = :writerProjectionVersion
			  AND newer.logical_fact_id = revision.logical_fact_id
			  AND newer.semantic_revision > revision.semantic_revision
		  )
		""",
	)
	suspend fun effectiveStepCount(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		logicalTrackingId: String,
	): Long?

	@Query("SELECT COUNT(*) FROM step_fact_revision")
	suspend fun countAll(): Long

	/**
	 * Bounded keyset page over every retained revision before retention trusts any row predicate.
	 *
	 * The unconstrained read is deliberate. A checksum-covered operation, wall time, run id, or
	 * semantic revision cannot be used to decide whether the row is worth authenticating. Mapping to
	 * [UnvalidatedStepFactRevision] also keeps malformed SQLite values visible to the caller instead
	 * of letting entity constructor validation make them disappear from the audit.
	 */
	@Query(
		"""
		SELECT *
		FROM step_fact_revision
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
		   OR (
			 writer_projection_id = :afterWriterProjectionId
			 AND writer_projection_version = :afterWriterProjectionVersion
			 AND logical_fact_id = :afterLogicalFactId
			 AND semantic_revision > :afterSemanticRevision
		   )
		ORDER BY writer_projection_id,
		         writer_projection_version,
		         logical_fact_id,
		         semantic_revision
		LIMIT :limit
		""",
	)
	suspend fun retentionAuditRevisionPage(
		afterWriterProjectionId: String?,
		afterWriterProjectionVersion: Long?,
		afterLogicalFactId: String?,
		afterSemanticRevision: Long?,
		limit: Int,
	): List<UnvalidatedStepFactRevision>

	/**
	 * Bounded keyset page of authenticated run ids with any UPSERT revision below the floor.
	 *
	 * The retention coordinator must first authenticate the complete table in the same Room
	 * transaction. The canonical Steps producer and ingress contract make interval end equal to
	 * acquisition time, so this authenticated wall-time predicate also enforces the acquisition-time
	 * floor. Superseded revisions remain discoverable: retention must never silently leave an old
	 * payload merely because a newer correction moved its current wall projection beyond the floor.
	 */
	@Query(
		"""
		SELECT DISTINCT service_run_id
		FROM step_fact_revision
		WHERE operation = 'UPSERT'
		  AND interval_end_time_ms < :beforeMs
		  AND service_run_id IS NOT NULL
		  AND (:afterServiceRunId IS NULL OR service_run_id > :afterServiceRunId)
		ORDER BY service_run_id
		LIMIT :limit
		""",
	)
	suspend fun retentionCandidateServiceRunIds(
		beforeMs: Long,
		afterServiceRunId: String?,
		limit: Int,
	): List<String>

	/**
	 * Removes UPSERT payloads for an exact, already-authenticated revision batch.
	 *
	 * The caller validates the complete lineage and checks the returned count inside the same Room
	 * transaction. The query contains no wall-time or run-scope fallback, so malformed retained fields
	 * cannot broaden deletion or reveal an older correction. Redacted RETRACT rows always survive.
	 */
	@Query(
		"""
		DELETE FROM step_fact_revision
		WHERE operation = 'UPSERT'
		  AND writer_projection_id = :writerProjectionId
		  AND writer_projection_version = :writerProjectionVersion
		  AND semantic_revision = :semanticRevision
		  AND logical_fact_id IN (:logicalFactIds)
		""",
	)
	suspend fun deleteAuthenticatedUpsertRevisions(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		semanticRevision: Long,
		logicalFactIds: List<String>,
	): Int

	@Query("DELETE FROM step_fact_revision")
	fun deleteAll()
}
