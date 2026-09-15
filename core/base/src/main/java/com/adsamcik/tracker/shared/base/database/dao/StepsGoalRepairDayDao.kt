package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.StepsGoalRepairDayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StepsGoalRepairDayDao {
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

	@Transaction
	suspend fun enqueue(epochDay: Long, sourceEvidenceRevision: Long) {
		require(sourceEvidenceRevision >= 0L)
		upsertUnchecked(epochDay, sourceEvidenceRevision)
	}

	@Query(
		"SELECT * FROM steps_goal_repair_day " +
			"ORDER BY source_evidence_revision ASC, epoch_day ASC LIMIT 1",
	)
	fun observeNext(): Flow<StepsGoalRepairDayEntity?>

	@Query(
		"SELECT * FROM steps_goal_repair_day " +
			"ORDER BY source_evidence_revision ASC, epoch_day ASC LIMIT 1",
	)
	suspend fun next(): StepsGoalRepairDayEntity?

	@Query("SELECT * FROM steps_goal_repair_day WHERE epoch_day = :epochDay LIMIT 1")
	suspend fun get(epochDay: Long): StepsGoalRepairDayEntity?

	@Query(
		"DELETE FROM steps_goal_repair_day WHERE epoch_day = :epochDay " +
			"AND source_evidence_revision = :sourceEvidenceRevision",
	)
	suspend fun removeIfExact(epochDay: Long, sourceEvidenceRevision: Long): Int

	@Query("DELETE FROM steps_goal_repair_day")
	fun deleteAll()
}
