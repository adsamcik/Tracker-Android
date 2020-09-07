package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

@Dao
interface ChallengeEntryDao : BaseDao<ChallengeEntry> {
	@Query("SELECT * FROM entry")
	fun getAll(): List<ChallengeEntry>

	@Query("SELECT * FROM entry WHERE start_time <= :time AND end_time >= :time")
	fun getActiveEntry(time: Long): List<ChallengeEntry>

	@Query("SELECT * FROM entry WHERE id = :id")
	fun get(id: Long): ChallengeEntry

	@Query("DELETE FROM entry")
	fun deleteAll()

	@Transaction
	fun insertSetId(item: ChallengeEntry) {
		val id = insert(item)
		if (id != -1L) {
			item.id = id
		}
	}
}
