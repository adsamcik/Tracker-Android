package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity

/** Narrow append-only storage boundary for system-provider Ambient Steps aggregates. */
@Dao
interface AmbientStepsFactRevisionDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insert(entity: AmbientStepsFactRevisionEntity): Long

	@Query(
		"SELECT * FROM ambient_steps_fact_revision WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision DESC LIMIT 1",
	)
	suspend fun latest(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): AmbientStepsFactRevisionEntity?

	@Query(
		"SELECT * FROM ambient_steps_fact_revision WHERE writer_id = :writerId " +
			"AND writer_version = :writerVersion AND logical_fact_id = :logicalFactId " +
			"ORDER BY semantic_revision ASC",
	)
	suspend fun revisions(
		writerId: String,
		writerVersion: Int,
		logicalFactId: String,
	): List<AmbientStepsFactRevisionEntity>

	/**
	 * Returns only the latest non-retracted fact state for one exact structural day authority.
	 * The correlated latest-revision predicate prevents a redacted RETRACT from losing its scope.
	 */
	@Query(
		"SELECT candidate.* FROM ambient_steps_fact_revision AS candidate " +
			"WHERE candidate.writer_id = :writerId AND candidate.writer_version = :writerVersion " +
			"AND candidate.structural_epoch_day = :epochDay " +
			"AND candidate.stored_zone_id = :storedZoneId " +
			"AND candidate.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND candidate.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state WHERE state.writer_id = candidate.writer_id " +
			"AND state.writer_version = candidate.writer_version " +
			"AND state.logical_fact_id = candidate.logical_fact_id) " +
			"ORDER BY candidate.window_start_time_ms ASC, candidate.logical_fact_id ASC",
	)
	suspend fun latestEffectiveForDay(
		writerId: String,
		writerVersion: Int,
		epochDay: Long,
		storedZoneId: String,
	): List<AmbientStepsFactRevisionEntity>

	/** Fixed-count range query for product composition; no per-row follow-up is required. */
	@Query(
		"SELECT candidate.* FROM ambient_steps_fact_revision AS candidate " +
			"WHERE candidate.writer_id = :writerId AND candidate.writer_version = :writerVersion " +
			"AND candidate.operation = '${AmbientStepsFactRevisionEntity.OPERATION_UPSERT}' " +
			"AND candidate.window_end_time_ms > :fromTimeMs " +
			"AND candidate.window_start_time_ms < :toTimeMs " +
			"AND candidate.semantic_revision = (SELECT MAX(state.semantic_revision) " +
			"FROM ambient_steps_fact_revision AS state WHERE state.writer_id = candidate.writer_id " +
			"AND state.writer_version = candidate.writer_version " +
			"AND state.logical_fact_id = candidate.logical_fact_id) " +
			"ORDER BY candidate.window_start_time_ms ASC, candidate.logical_fact_id ASC LIMIT :limit",
	)
	suspend fun latestEffectiveOverlapping(
		writerId: String,
		writerVersion: Int,
		fromTimeMs: Long,
		toTimeMs: Long,
		limit: Int,
	): List<AmbientStepsFactRevisionEntity>

	@Query("SELECT COUNT(*) FROM ambient_steps_fact_revision")
	suspend fun countAll(): Long

	/** Full collected-data clear only; scoped deletion appends redacted revisions instead. */
	@Query("DELETE FROM ambient_steps_fact_revision")
	fun deleteAll()
}
