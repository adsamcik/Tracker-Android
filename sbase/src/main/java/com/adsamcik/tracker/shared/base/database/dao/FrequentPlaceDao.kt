package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.FrequentPlaceEntity

@Dao
interface FrequentPlaceDao {
	@Query("SELECT * FROM frequent_place ORDER BY last_visit_ms DESC LIMIT :limit")
	suspend fun getAll(limit: Int = 500): List<FrequentPlaceEntity>

	@Insert
	suspend fun insertAndGetId(place: FrequentPlaceEntity): Long

	@Query("UPDATE frequent_place SET visit_count = visit_count + 1, last_visit_ms = :lastVisitMs WHERE id = :id")
	suspend fun incrementVisitCount(id: Long, lastVisitMs: Long)

	@Query("DELETE FROM frequent_place")
	fun deleteAll()
    @Query("DELETE FROM frequent_place WHERE last_visit_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
