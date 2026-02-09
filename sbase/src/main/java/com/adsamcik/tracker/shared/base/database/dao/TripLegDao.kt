package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.TripLegEntity

@Dao
interface TripLegDao {
	@Insert
	suspend fun insertAll(legs: List<TripLegEntity>)

	@Query("SELECT * FROM trip_leg WHERE trip_id = :tripId ORDER BY sequence_index")
	suspend fun getByTripId(tripId: Long): List<TripLegEntity>

	@Query("DELETE FROM trip_leg")
	fun deleteAll()
    @Query("DELETE FROM trip_leg WHERE start_time_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
