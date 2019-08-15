package com.adsamcik.signalcollector.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.signalcollector.common.database.dao.BaseDao
import com.adsamcik.signalcollector.game.challenge.database.data.ChallengeEntry

@Dao
interface ChallengeEntryDao : BaseDao<ChallengeEntry> {
	@Query("SELECT * FROM entry")
	fun getAll(): List<ChallengeEntry>

	@Query("SELECT * FROM entry WHERE start_time <= :time AND end_time >= :time")
	fun getActiveEntry(time: Long): List<ChallengeEntry>

	@Query("SELECT * FROM entry WHERE id = :id")
	fun get(id: Long): ChallengeEntry

	@Transaction
	fun insertSetId(item: ChallengeEntry) {
		val id = insert(item)
		if (id != -1L) {
			item.id = id
		}
	}
}
