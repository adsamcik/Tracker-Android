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
		"SELECT * FROM step_fact_revision " +
			"WHERE logical_fact_id = :logicalFactId AND semantic_revision = :semanticRevision",
	)
	suspend fun revision(logicalFactId: String, semanticRevision: Long): StepFactRevisionEntity?

	@Query("SELECT * FROM step_fact_revision WHERE mutation_id = :mutationId")
	suspend fun mutation(mutationId: String): StepFactRevisionEntity?

	@Query(
		"SELECT * FROM step_fact_revision WHERE logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT 1",
	)
	suspend fun latest(logicalFactId: String): StepFactRevisionEntity?

	@Query(
		"SELECT * FROM step_fact_revision WHERE logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision ASC",
	)
	suspend fun revisions(logicalFactId: String): List<StepFactRevisionEntity>

	/** Latest effective revisions whose immutable observations overlap the requested wall-time range. */
	@Query(
		"""
		SELECT revision.* FROM step_fact_revision AS revision
		INNER JOIN step_interval AS interval ON interval.id = revision.step_interval_id
		WHERE revision.logical_tracking_id = :logicalTrackingId
		  AND revision.purpose = 'SESSION_CAPTURE'
		  AND interval.end_time_ms >= :fromMs
		  AND interval.start_time_ms <= :toMs
		  AND NOT EXISTS (
			SELECT 1 FROM step_fact_revision AS newer
			WHERE newer.logical_fact_id = revision.logical_fact_id
			  AND newer.semantic_revision > revision.semantic_revision
		  )
		ORDER BY interval.start_time_ms ASC, interval.id ASC
		""",
	)
	suspend fun latestEffectiveBetween(
		logicalTrackingId: String,
		fromMs: Long,
		toMs: Long,
	): List<StepFactRevisionEntity>

	@Query(
		"""
		SELECT COALESCE(SUM(revision.effective_step_count), 0)
		FROM step_fact_revision AS revision
		WHERE revision.logical_tracking_id = :logicalTrackingId
		  AND revision.purpose = 'SESSION_CAPTURE'
		  AND NOT EXISTS (
			SELECT 1 FROM step_fact_revision AS newer
			WHERE newer.logical_fact_id = revision.logical_fact_id
			  AND newer.semantic_revision > revision.semantic_revision
		  )
		""",
	)
	suspend fun effectiveStepCount(logicalTrackingId: String): Long

	@Query("SELECT COUNT(*) FROM step_fact_revision")
	suspend fun countAll(): Long

	@Query("DELETE FROM step_fact_revision")
	fun deleteAll()
}
