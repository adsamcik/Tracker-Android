package com.adsamcik.signalcollector.common.database.dao

import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.common.database.data.TrackerSessionSummary
import com.adsamcik.signalcollector.common.database.data.TrackerSessionTimeSummary

@Dao
interface SessionDataDao : BaseDao<TrackerSession> {

	@Query("SELECT * FROM tracker_session WHERE id = :id")
	fun get(id: Long): TrackerSession?

	@Query("SELECT * FROM tracker_session WHERE id = :id")
	fun getLive(id: Long): LiveData<TrackerSession>

	@Query("SELECT * FROM tracker_session")
	fun getAll(): List<TrackerSession>


	@Query("SELECT * FROM tracker_session ORDER BY start DESC")
	fun getAllPaged(): DataSource.Factory<Int, TrackerSession>

	@Query("SELECT SUM(`end` - start) as duration, SUM(steps) as steps, SUM(collections) as collections, SUM(distance) as distance, SUM(distance_in_vehicle) as distance_in_vehicle, SUM(distance_on_foot) as distance_on_foot FROM tracker_session")
	fun getSummary(): TrackerSessionSummary

	@Query("SELECT SUM(`end` - start) as duration, SUM(steps) as steps, SUM(collections) as collections, SUM(distance) as distance, SUM(distance_in_vehicle) as distance_in_vehicle, SUM(distance_on_foot) as distance_on_foot FROM tracker_session WHERE start >= :from AND start <= :to")
	fun getSummary(from: Long, to: Long): TrackerSessionSummary


	//(round(timestamp / 86400000.0 - 0.5) * 86400000.0) should round down to date
	@Query("SELECT ((start / 86400000) * 86400000) as time, SUM(`end` - start) as duration, SUM(steps) as steps, SUM(collections) as collections, SUM(distance) as distance, SUM(distance_in_vehicle) as distance_in_vehicle, SUM(distance_on_foot) as distance_on_foot FROM tracker_session WHERE start >= :from AND start <= :to GROUP BY ((start / 86400000) * 86400000) ORDER BY time DESC")
	fun getSummaryByDays(from: Long, to: Long): List<TrackerSessionTimeSummary>

	@Query("SELECT * FROM tracker_session WHERE datetime(start, 'start of day') == datetime(:day, 'start of day')")
	fun getForDay(day: Long): List<TrackerSession>

	@Query("SELECT * FROM tracker_session WHERE start >= :from AND start <= :to ORDER BY start DESC")
	fun getBetween(from: Long, to: Long): List<TrackerSession>

	@Query("SELECT * FROM tracker_session ORDER BY id DESC LIMIT :count")
	fun getLast(count: Int): TrackerSession?

	@Transaction
	fun continueTrackerSession(maxAgeMillis: Long): TrackerSession {
		val lastSession = getLast(1)
		return if (lastSession != null && Time.nowMillis - lastSession.end <= maxAgeMillis) {
			lastSession
		} else {
			//Common package does not contain MutableTrackerSession by design
			val id = insert(TrackerSession())
			TrackerSession(id)
		}
	}

	@Query("SELECT COUNT(*) FROM tracker_session")
	fun count(): Long

}