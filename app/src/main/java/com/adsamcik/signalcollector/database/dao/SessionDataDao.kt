package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.signalcollector.data.TrackingSession

@Dao
interface SessionDataDao {
	@Insert
	fun insert(session: TrackingSession): Long

	@Update
	fun update(session: TrackingSession)

	@Query("SELECT * FROM tracking_session")
	fun getAll(): List<TrackingSession>

	@Query("SELECT * FROM tracking_session WHERE datetime(start, 'start of day') == datetime(:day, 'start of day')")
	fun getForDay(day: Long): List<TrackingSession>

}