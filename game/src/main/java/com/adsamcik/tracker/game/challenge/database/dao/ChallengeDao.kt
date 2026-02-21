package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

@Dao
interface ChallengeDao : BaseDao<ChallengeEntity> {
	@Query("SELECT * FROM challenge WHERE end_time > :now AND is_completed = 0")
	fun getActive(now: Long): List<ChallengeEntity>

	@Query("SELECT * FROM challenge WHERE id = :id")
	fun get(id: Long): ChallengeEntity?

	@Query("SELECT * FROM challenge")
	fun getAll(): List<ChallengeEntity>

	@Query("DELETE FROM challenge WHERE id = :id")
	fun deleteById(id: Long)

	@Query("SELECT COUNT(*) FROM challenge WHERE end_time > :now AND is_completed = 0")
	fun getActiveCount(now: Long): Int
}
