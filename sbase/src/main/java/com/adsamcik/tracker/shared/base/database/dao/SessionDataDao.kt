package com.adsamcik.tracker.shared.base.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.data.DateRange
import com.adsamcik.tracker.shared.base.database.data.TodaySessionSummary
import com.adsamcik.tracker.shared.base.database.data.TrackerSessionSummary
import com.adsamcik.tracker.shared.base.database.data.TrackerSessionTimeSummary

/**
 * Data access object for session data.
 */
@Dao
interface SessionDataDao : BaseDao<TrackerSession> {

	/**
	 * Deletes all sessions from database.
	 */
	@Query("DELETE FROM tracker_session")
	fun deleteAll()

	/**
	 * Finds specific session in database.
	 *
	 * @return Returns specific session or null if session does not exist.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM tracker_session WHERE id = :id")
	fun get(id: Long): TrackerSession?



	/**
	 * Finds all sessions in database.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM tracker_session LIMIT 10000")
	fun getAll(): List<TrackerSession>

	/**
	 * Finds all session that were active between [from] (inclusive) and [to].(inclusive).
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from tracker_session where `end` >= :from and start <= :to")
	fun getAllBetween(from: Long, to: Long): List<TrackerSession>

	/**
	 * Returns all paged ordered descending by the start of the session.
	 */
	@Query("SELECT * FROM tracker_session ORDER BY start DESC")
	fun getAllPaged(): PagingSource<Int, TrackerSession>

	/**
	 * Calculates a summary of all session in the database.
	 */
	@Query(
			"""
		SELECT
			SUM(`end` - start) as duration,
			SUM(steps) as steps,
			SUM(collections) as collections,
			SUM(distance) as distance,
			SUM(distance_in_vehicle) as distance_in_vehicle,
			SUM(distance_on_foot) as distance_on_foot
		FROM tracker_session"""
	)
	fun getSummary(): TrackerSessionSummary

	/**
	 * Calculates a summary of all sessions between [from] (inclusive) and [to] (inclusive).
	 */
	@Query(
			"""
		SELECT
			SUM(`end` - start) as duration,
			SUM(steps) as steps,
			SUM(collections) as collections,
			SUM(distance) as distance,
			SUM(distance_in_vehicle) as distance_in_vehicle,
			SUM(distance_on_foot) as distance_on_foot
		FROM tracker_session
		WHERE
			start >= :from AND
			start <= :to
		"""
	)
	fun getSummary(from: Long, to: Long): TrackerSessionSummary

	/**
	 * Calculates a summary of all sessions grouped by day between [from] (inclusive) and [to] (inclusive).
	 *
	 * @param offsetMillis Timezone offset in milliseconds to adjust start times correctly for local days.
	 */
	@Query(
			"""
		SELECT
			(((start + :offsetMillis) / 86400000) * 86400000 - :offsetMillis) as time,
			SUM(`end` - start) as duration,
			SUM(steps) as steps,
			SUM(collections) as collections,
			SUM(distance) as distance,
			SUM(distance_in_vehicle) as distance_in_vehicle,
			SUM(distance_on_foot) as distance_on_foot
		FROM tracker_session
		WHERE
			start >= :from AND
			start <= :to
		GROUP BY ((start + :offsetMillis) / 86400000)
		ORDER BY time DESC"""
	)
	fun getSummaryByDays(from: Long, to: Long, offsetMillis: Long): List<TrackerSessionTimeSummary>

	/**
	 * Finds all sessions that started on a specific day.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM tracker_session WHERE datetime(start, 'start of day') == datetime(:day, 'start of day')")
	fun getForDay(day: Long): List<TrackerSession>

	/**
	 * Finds all sessions that started between [from] (inclusive) and [to] (inclusive).
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM tracker_session WHERE start >= :from AND start <= :to ORDER BY start DESC")
	fun getBetween(from: Long, to: Long): List<TrackerSession>

	/**
	 * Finds number of session from the end.
	 *
	 * @param count Number of session from the end to return.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * FROM tracker_session ORDER BY id DESC LIMIT :count")
	fun getLast(count: Int): TrackerSession?

	/**
	 * Finds an active session that is not older than [maxAgeMillis].
	 * If no session is found, new one is created and saved inside a database.
	 */
	@Transaction
	suspend fun continueTrackerSession(maxAgeMillis: Long): TrackerSession {
		val lastSession = getLast(1)
		return if (lastSession != null && Time.nowMillis - lastSession.end <= maxAgeMillis) {
			lastSession
		} else {
			//Common package does not contain MutableTrackerSession by design
			val id = insert(TrackerSession())
			TrackerSession(id)
		}
	}

	/**
	 * Calculates a summary of all sessions that started today.
	 * Uses suspend for on-demand async fetching without blocking.
	 *
	 * @param todayStart start of today in millis (midnight UTC or local)
	 * @param now current time in millis
	 * @return Summary of today's sessions or null values if no sessions exist
	 */
	@Query(
		"""
		SELECT
			SUM(`end` - start) as duration,
			SUM(steps) as steps,
			SUM(collections) as collections,
			SUM(distance) as distance,
			SUM(distance_in_vehicle) as distance_in_vehicle,
			SUM(distance_on_foot) as distance_on_foot,
			COUNT(*) as session_count
		FROM tracker_session
		WHERE
			start >= :todayStart AND
			start <= :now
		"""
	)
	suspend fun getTodaySummary(todayStart: Long, now: Long): TodaySessionSummary?

	/**
	 * Counts all sessions in the database.
	 */
	@Query("SELECT COUNT(*) FROM tracker_session")
	fun count(): Long

	/**
	 * Counts all sessions in the database.
	 */
	@Query("SELECT COUNT(*) FROM tracker_session WHERE :to >= start AND :from <= `end`")
	fun count(from: Long, to: Long): Long

	/**
	 * Creates range from start of the first session and end of the last session.
	 */
	@Query("SELECT MIN(start) as start, MAX(`end`) as endInclusive from tracker_session")
	fun range(): DateRange
}
