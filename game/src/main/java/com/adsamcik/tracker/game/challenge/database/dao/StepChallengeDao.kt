package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.dao.BaseDao
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity

@Dao
interface StepChallengeDao : BaseDao<StepChallengeEntity> {
	@Query("SELECT * FROM challenge_step WHERE id == :id")
	fun get(id: Long): StepChallengeEntity

	@Query("SELECT * FROM challenge_step WHERE entry_id == :entryId")
	fun getByEntry(entryId: Long): StepChallengeEntity
}
