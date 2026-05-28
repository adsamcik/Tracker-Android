package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExplorationStreakDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsert(streak: ExplorationStreakEntity)

	@Query("SELECT * FROM exploration_streak WHERE type = :type")
	suspend fun getByType(type: String): ExplorationStreakEntity?

	@Query("SELECT * FROM exploration_streak WHERE type = :type")
	fun getByTypeFlow(type: String): Flow<ExplorationStreakEntity?>

	@Query("SELECT * FROM exploration_streak")
	suspend fun getAll(): List<ExplorationStreakEntity>

	@Query("""
		UPDATE exploration_streak
		SET current_count = current_count + 1,
			best_count = MAX(best_count, current_count + 1),
			last_increment_day = :epochDay,
			updated_at = :updatedAt
		WHERE type = :type
	""")
	suspend fun incrementStreak(type: String, epochDay: Long, updatedAt: Long)

	@Query("""
		UPDATE exploration_streak
		SET current_count = 0,
			updated_at = :updatedAt
		WHERE type = :type
	""")
	suspend fun resetStreak(type: String, updatedAt: Long)

	@Query("DELETE FROM exploration_streak")
	fun deleteAll()

	/**
	 * Lifetime streak bests (best_count) are user accomplishments — never retention-aged.
	 * This sweep only clears stale rows whose best_count is 0 (never had a streak), so
	 * abandoned/empty rows can still be garbage-collected without nuking the personal
	 * best. Use [deleteAll] for an explicit user-initiated reset.
	 */
	@Query("DELETE FROM exploration_streak WHERE updated_at < :beforeMs AND best_count = 0")
	suspend fun deleteOlderThan(beforeMs: Long): Int
}
