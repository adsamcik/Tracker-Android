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

	@Query("DELETE FROM step_fact_revision")
	fun deleteAll()
}
