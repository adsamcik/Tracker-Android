package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.TrackerStateEvent

@Dao
interface TrackerStateEventDao {
	@Insert
	suspend fun insert(event: TrackerStateEvent): Long

	@Query(
		"""
		SELECT * FROM tracker_state_event
		WHERE wall_time_ms < :toMs AND wall_time_ms >= :fromMs
		ORDER BY wall_time_ms ASC, id ASC
		""",
	)
	suspend fun getBetween(fromMs: Long, toMs: Long): List<TrackerStateEvent>

	@Query("DELETE FROM tracker_state_event WHERE wall_time_ms < :beforeMs")
	suspend fun deleteOlderThan(beforeMs: Long): Int

	@Query("DELETE FROM tracker_state_event")
	fun deleteAll()
}
