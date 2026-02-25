package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeStreakEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

@Dao
interface ChallengeStreakDao : BaseDao<ChallengeStreakEntity> {
	@Query("SELECT * FROM challenge_streak WHERE id = 1")
	fun get(): ChallengeStreakEntity?

	@Query("SELECT * FROM challenge_streak WHERE id = 1")
	fun observe(): Flow<ChallengeStreakEntity?>

	@Query("INSERT OR IGNORE INTO challenge_streak (id, current_count, best_count, last_completion_time, freeze_count) VALUES (1, 0, 0, 0, 0)")
	fun ensureExists()
}
