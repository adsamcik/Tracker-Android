package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.InferredTripEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InferredTripDao {
	@Insert
	suspend fun insertAndGetId(trip: InferredTripEntity): Long

	@Query("SELECT * FROM inferred_trip WHERE start_time_ms >= :startMs AND end_time_ms <= :endMs ORDER BY start_time_ms")
	suspend fun getAllBetween(startMs: Long, endMs: Long): List<InferredTripEntity>

	@Query("SELECT * FROM inferred_trip ORDER BY start_time_ms DESC LIMIT 500")
	fun getAllFlow(): Flow<List<InferredTripEntity>>

	@Query("SELECT * FROM inferred_trip WHERE id = :id")
	suspend fun getById(id: Long): InferredTripEntity?

	@Query("DELETE FROM inferred_trip")
	fun deleteAll()
    @Query("DELETE FROM inferred_trip WHERE start_time_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
