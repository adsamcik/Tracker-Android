package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeHistoryEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeHistoryDao : BaseDao<ChallengeHistoryEntity> {
	@Query("SELECT * FROM challenge_history ORDER BY COALESCE(completed_at, end_time) DESC")
	fun observeAll(): Flow<List<ChallengeHistoryEntity>>

	@Query("SELECT * FROM challenge_history ORDER BY COALESCE(completed_at, end_time) DESC")
	suspend fun getAll(): List<ChallengeHistoryEntity>

	@Query("SELECT outcome FROM challenge_history ORDER BY id DESC LIMIT :limit")
	fun getRecentOutcomes(limit: Int = 10): List<String>

	/**
	 * Completion ratio (0.0..1.0) over the last [limit] history rows, computed in SQL.
	 * Returns null if no history exists.
	 */
	@Query(
		"""
		SELECT AVG(CASE WHEN outcome = 'COMPLETED' THEN 1.0 ELSE 0.0 END)
		FROM (
		  SELECT outcome FROM challenge_history ORDER BY id DESC LIMIT :limit
		)
		"""
	)
	suspend fun getRecentCompletionRate(limit: Int = 10): Double?

	@Query("SELECT * FROM challenge_history WHERE id = :id")
	suspend fun get(id: Long): ChallengeHistoryEntity?

	@Query("SELECT * FROM challenge_history WHERE outcome = 'COMPLETED' ORDER BY completed_at DESC")
	suspend fun getCompleted(): List<ChallengeHistoryEntity>

	@Query("SELECT * FROM challenge_history WHERE medal = :medal ORDER BY completed_at DESC")
	suspend fun getByMedal(medal: String): List<ChallengeHistoryEntity>

	@Query("SELECT COUNT(*) FROM challenge_history WHERE outcome = 'COMPLETED'")
	fun getCompletedCount(): Int

	@Query("SELECT COUNT(*) FROM challenge_history")
	fun getTotalCount(): Int

	@Query("SELECT COUNT(*) FROM challenge_history WHERE medal = :medal")
	fun getMedalCount(medal: String): Int

	@Query("SELECT COUNT(*) FROM challenge_history WHERE outcome = 'COMPLETED'")
	fun observeCompletedCount(): Flow<Int>

	@Query("SELECT COUNT(*) FROM challenge_history WHERE medal = :medal")
	fun observeMedalCount(medal: String): Flow<Int>

	/**
	 * Aggregate lifetime stats in a single query. Replaces `observeAll().map { count/sum/filter }`
	 * patterns that materialize the whole history list every emission (p5-1).
	 */
	@Query(
		"""
		SELECT
		  COUNT(*) AS totalChallenges,
		  COALESCE(SUM(CASE WHEN outcome = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedCount,
		  COALESCE(SUM(CASE WHEN medal = 'GOLD'   THEN 1 ELSE 0 END), 0) AS goldCount,
		  COALESCE(SUM(CASE WHEN medal = 'SILVER' THEN 1 ELSE 0 END), 0) AS silverCount,
		  COALESCE(SUM(CASE WHEN medal = 'BRONZE' THEN 1 ELSE 0 END), 0) AS bronzeCount,
		  COALESCE(SUM(xp_awarded), 0) AS totalXpEarned
		FROM challenge_history
		"""
	)
	fun observeLifetimeStats(): Flow<LifetimeStatsRow>
}

/**
 * Row projection for [ChallengeHistoryDao.observeLifetimeStats]. Mirrors the fields of
 * `LifetimeStatsUi` so the repository can map directly without re-aggregating in Kotlin.
 */
data class LifetimeStatsRow(
	val totalChallenges: Int,
	val completedCount: Int,
	val goldCount: Int,
	val silverCount: Int,
	val bronzeCount: Int,
	val totalXpEarned: Long,
)
