package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.game.challenge.data.entity.ActiveTimeChallengeEntity
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

@Dao
interface ActiveTimeChallengeDao : BaseDao<ActiveTimeChallengeEntity> {

    @Query("SELECT * FROM challenge_active_time WHERE id == :id")
    fun get(id: Long): ActiveTimeChallengeEntity

    @Query("SELECT * FROM challenge_active_time WHERE entry_id == :entryId")
    fun getByEntry(entryId: Long): ActiveTimeChallengeEntity
}
