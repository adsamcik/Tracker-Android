package com.adsamcik.tracker.game.challenge.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

/**
 * Data access object for challenge entries.
 */
@Dao
interface ChallengeEntryDao : BaseDao<ChallengeEntry> {
	/**
	 * Returns list containing all challenge entries.
	 */
	@Query("SELECT * FROM entry")
	fun getAll(): List<ChallengeEntry>

	/**
	 * Returns list of all active challenge entries.
	 */
	@Query("SELECT * FROM entry WHERE start_time <= :time AND end_time >= :time")
	fun getActiveEntry(time: Long): List<ChallengeEntry>

	/**
	 * Returns a specific challenge entry.
	 */
	@Query("SELECT * FROM entry WHERE id = :id")
	fun get(id: Long): ChallengeEntry

	/**
	 * Removes all challenge entries from the database.
	 */
	@Query("DELETE FROM entry")
	fun deleteAll()

	/**
	 * Inserts a challenge entry and sets its database id as id.
	 */
	@Transaction
	fun insertSetId(item: ChallengeEntry) {
		val id = insert(item)
		if (id != -1L) {
			item.id = id
		}
	}
}
