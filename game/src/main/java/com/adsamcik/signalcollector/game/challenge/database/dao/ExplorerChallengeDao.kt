package com.adsamcik.signalcollector.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.signalcollector.common.database.dao.BaseDao
import com.adsamcik.signalcollector.game.challenge.data.entity.ExplorerChallengeEntity

@Dao
interface ExplorerChallengeDao : BaseDao<ExplorerChallengeEntity> {

	@Query("SELECT * FROM challenge_explorer WHERE id == :id")
	fun get(id: Long): ExplorerChallengeEntity

	@Query("SELECT * FROM challenge_explorer WHERE entry_id == :entryId")
	fun getByEntry(entryId: Long): ExplorerChallengeEntity
}
