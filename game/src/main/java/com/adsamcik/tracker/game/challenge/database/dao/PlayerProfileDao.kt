package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.game.challenge.database.entity.PlayerProfileEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerProfileDao : BaseDao<PlayerProfileEntity> {
	@Query("SELECT * FROM player_profile WHERE id = 1")
	fun get(): PlayerProfileEntity?

	@Query("SELECT * FROM player_profile WHERE id = 1")
	fun observe(): Flow<PlayerProfileEntity?>

	@Query("INSERT OR IGNORE INTO player_profile (id, total_xp, level, xp_into_current_level, xp_for_next_level) VALUES (1, 0, 1, 0, 30)")
	fun ensureExists()
}
