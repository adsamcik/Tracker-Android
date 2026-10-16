package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.StepsGoalRepairDayEntity
import kotlinx.coroutines.flow.Flow

/**
 * Queues civil days whose Steps goal effects need repair, preserving the newest source-evidence
 * revision for each day.
 */
@Dao
interface StepsGoalRepairDayDao {
	/**
	 * Writes `steps_goal_repair_day` rows using the SQL conflict policy. Day keys use civil-calendar
	 * epoch days.
	 */
	@Query(
		"""
		INSERT INTO steps_goal_repair_day(epoch_day, source_evidence_revision)
		VALUES (:epochDay, :sourceEvidenceRevision)
		ON CONFLICT(epoch_day) DO UPDATE SET
			source_evidence_revision = excluded.source_evidence_revision
		WHERE excluded.source_evidence_revision > steps_goal_repair_day.source_evidence_revision
		""",
	)
	suspend fun upsertUnchecked(epochDay: Long, sourceEvidenceRevision: Long)

	/**
	 * Queues a civil epoch day for Steps goal repair after validating a nonnegative source revision;
	 * newer revisions supersede older requests.
	 */
	@Transaction
	suspend fun enqueue(epochDay: Long, sourceEvidenceRevision: Long) {
		require(sourceEvidenceRevision >= 0L)
		upsertUnchecked(epochDay, sourceEvidenceRevision)
	}

	/**
	 * Observes `steps_goal_repair_day` rows. Orders by `source_evidence_revision ASC, epoch_day ASC`.
	 * Returns at most 1 row.
	 */
	@Query(
		"SELECT * FROM steps_goal_repair_day " +
			"ORDER BY source_evidence_revision ASC, epoch_day ASC LIMIT 1",
	)
	fun observeNext(): Flow<StepsGoalRepairDayEntity?>

	/**
	 * Reads `steps_goal_repair_day` rows. Orders by `source_evidence_revision ASC, epoch_day ASC`.
	 * Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM steps_goal_repair_day " +
			"ORDER BY source_evidence_revision ASC, epoch_day ASC LIMIT 1",
	)
	suspend fun next(): StepsGoalRepairDayEntity?

	/**
	 * Reads `steps_goal_repair_day` rows. Query conditions include `epoch_day = :epochDay`. Returns
	 * at most 1 row. Returns null when no row matches. Day keys use civil-calendar epoch days.
	 */
	@Query("SELECT * FROM steps_goal_repair_day WHERE epoch_day = :epochDay LIMIT 1")
	suspend fun get(epochDay: Long): StepsGoalRepairDayEntity?

	/**
	 * Deletes `steps_goal_repair_day` rows matching the supplied scope. Query conditions include
	 * `epoch_day = :epochDay`, `source_evidence_revision = :sourceEvidenceRevision`. Returns the
	 * affected row count. Day keys use civil-calendar epoch days.
	 */
	@Query(
		"DELETE FROM steps_goal_repair_day WHERE epoch_day = :epochDay " +
			"AND source_evidence_revision = :sourceEvidenceRevision",
	)
	suspend fun removeIfExact(epochDay: Long, sourceEvidenceRevision: Long): Int

	/**
	 * Deletes `steps_goal_repair_day` rows.
	 */
	@Query("DELETE FROM steps_goal_repair_day")
	fun deleteAll()
}
