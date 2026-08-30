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
		""",
	)
	suspend fun latestStatesForServiceRun(
		writerProjectionId: String,
		writerProjectionVersion: Int,
		writerBindingGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevisions: List<Long>,
	): List<StepFactRevisionEntity>

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
	 * Removes retained product payloads whose effective interval is below the raw-data floor.
	 *
	 * When the latest UPSERT for a fact expires, every older UPSERT revision for that fact is also
	 * removed. Otherwise retention could expose an older attribution as the new latest state and
	 * resurrect a corrected service-run value. Independently expired historical revisions are still
	 * minimized even while a newer effective correction remains retained.
	 *
	 * Redacted retractions deliberately survive so an already-known fact cannot reappear within its
	 * writer contract. A source deletion fence is separately required for replay, import, or another
	 * writer version and every such mutation path must consult it transactionally.
	 */
	@Query(
		"""
		DELETE FROM step_fact_revision
		WHERE operation = 'UPSERT' AND (
			interval_end_time_ms < :beforeMs OR
			(writer_projection_id, writer_projection_version, logical_fact_id) IN (
				SELECT latest_upsert.writer_projection_id,
				       latest_upsert.writer_projection_version,
				       latest_upsert.logical_fact_id
				FROM step_fact_revision AS latest_upsert
				WHERE latest_upsert.operation = 'UPSERT'
				  AND latest_upsert.interval_end_time_ms < :beforeMs
				  AND NOT EXISTS (
					SELECT 1 FROM step_fact_revision AS newer_upsert
					WHERE newer_upsert.writer_projection_id =
						latest_upsert.writer_projection_id
					  AND newer_upsert.writer_projection_version =
						latest_upsert.writer_projection_version
					  AND newer_upsert.logical_fact_id = latest_upsert.logical_fact_id
					  AND newer_upsert.operation = 'UPSERT'
					  AND newer_upsert.semantic_revision > latest_upsert.semantic_revision
				  )
			)
		)
		""",
	)
	suspend fun deleteUpsertsEndingBefore(beforeMs: Long): Int

	@Query("DELETE FROM step_fact_revision")
	fun deleteAll()
}
