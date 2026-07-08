package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import com.adsamcik.tracker.shared.base.database.data.MiniGameScoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MiniGameScoreDao : BaseDao<MiniGameScoreEntity> {
	@Query("SELECT * FROM minigame_score WHERE game_id = :gameId ORDER BY score DESC")
	fun getScoresByGame(gameId: String): Flow<List<MiniGameScoreEntity>>

	@Query("SELECT MAX(score) FROM minigame_score WHERE game_id = :gameId")
	fun getHighScore(gameId: String): Double?

	@Query("SELECT * FROM minigame_score ORDER BY played_at DESC LIMIT :limit")
	fun getRecent(limit: Int = 20): Flow<List<MiniGameScoreEntity>>
	/** Total number of mini-game runs recorded. */
	@Query("SELECT COUNT(*) FROM minigame_score")
	suspend fun countTotal(): Long
	@Query("DELETE FROM minigame_score")
	fun deleteAll()

}
