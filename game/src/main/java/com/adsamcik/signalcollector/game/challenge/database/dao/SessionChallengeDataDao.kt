package com.adsamcik.signalcollector.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.signalcollector.common.database.dao.BaseDao
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeSessionData

@Dao
interface SessionChallengeDataDao : BaseDao<ChallengeSessionData> {
	@Query("SELECT * FROM challenge_session_data WHERE id = :id")
	fun get(id: Long): ChallengeSessionData?
}