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
}
