package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.shared.base.database.data.DateRange
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

/**
 * Location data access object for basic location data in database.
 */
@Dao
interface LocationDataDao : BaseDao<DatabaseLocation> {
	/**
	 * Delete all location data.
	 */
	@Query("DELETE from location_data")
	fun deleteAll()

	/**
	 * Get all location data with time constraints.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from location_data where time >= :from and time <= :to")
	fun getAllBetween(from: Long, to: Long): List<DatabaseLocation>

	/**
	 * Get all location data with time constraints ordered by time.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from location_data where time >= :from and time <= :to ORDER BY time")
	fun getAllBetweenOrdered(from: Long, to: Long): List<DatabaseLocation>

	/**
	 * Get a page of location data within a time range, ordered by time.
	 * Used for chunked/streaming export to avoid loading entire datasets into memory.
	 *
	 * @param from Start time (epoch millis, inclusive)
	 * @param to End time (epoch millis, inclusive)
	 * @param limit Maximum number of rows to return
	 * @param offset Number of rows to skip
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from location_data where time >= :from and time <= :to ORDER BY time LIMIT :limit OFFSET :offset")
	fun getBetweenPaged(from: Long, to: Long, limit: Int, offset: Int): List<DatabaseLocation>

	/**
	 * Get all location data more recent than [from].
	 *
	 * @param from Start time (epoch millis, inclusive)
	 * @param limit Maximum number of rows to return (safety cap to prevent OOM)
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from location_data where time >= :from ORDER BY time LIMIT :limit")
	fun getAllSince(from: Long, limit: Int = 50_000): List<DatabaseLocation>

	/**
	 * Get all location data with area constraints.
	 */
	@Query(
			"""
		SELECT time, lon, lat, hor_acc as weight
		FROM location_data
		where
			lat >= :bottomLatitude and
			lon >= :leftLongitude and
			lat <= :topLatitude and
			lon <= :rightLongitude
		"""
	)
	fun getAllInside(
			topLatitude: Double,
			rightLongitude: Double,
			bottomLatitude: Double,
			leftLongitude: Double
	): List<TimeLocation2DWeighted>

	/**
	 * Get all location data with area and time constraints.
	 */
	@Query(
			"""
		SELECT time, lon, lat, hor_acc as weight
		FROM location_data
		where
			time >= :from and
			time <= :to and
			lat >= :bottomLatitude and
			lon >= :leftLongitude and
			lat <= :topLatitude and
			lon <= :rightLongitude
		"""
	)
	fun getAllInsideAndBetween(
			from: Long,
			to: Long,
			topLatitude: Double,
			rightLongitude: Double,
			bottomLatitude: Double,
			leftLongitude: Double
	): List<TimeLocation2DWeighted>

	/**
	 * Get all location data with speed with area constraints.
	 */
	@Query(
		"""
		SELECT time, lon, lat, speed as weight
		FROM location_data
		where
			lat >= :bottomLatitude and
			lon >= :leftLongitude and
			lat <= :topLatitude and
			lon <= :rightLongitude
		"""
	)
	fun getAllInsideSpeed(
		topLatitude: Double,
		rightLongitude: Double,
		bottomLatitude: Double,
		leftLongitude: Double
	): List<TimeLocation2DWeighted>

	/**
	 * Get all location data with speed with area and time constraints.
	 */
	@Query(
		"""
		SELECT time, lon, lat, speed as weight
		FROM location_data
		where
			time >= :from and
			time <= :to and
			lat >= :bottomLatitude and
			lon >= :leftLongitude and
			lat <= :topLatitude and
			lon <= :rightLongitude
		"""
	)
	fun getAllInsideAndBetweenSpeed(
		from: Long,
		to: Long,
		topLatitude: Double,
		rightLongitude: Double,
		bottomLatitude: Double,
		leftLongitude: Double
	): List<TimeLocation2DWeighted>

	/**
	 * Count all location records in database.
	 */
	@Query("SELECT COUNT(*) FROM location_data")
	fun count(): Long

	/**
	 * Count all location records in database.
	 */
	@Query("SELECT COUNT(*) FROM location_data WHERE time >= :from and time <= :to")
	fun count(from: Long, to: Long): Long

	/**
	 * Count all location data with area and time constraints.
	 */
	@Query(
			"""
		SELECT COUNT(*)
		FROM location_data
		where
			time >= :from and
			time <= :to and
			lat >= :bottomLatitude and
			lon >= :leftLongitude and
			lat <= :topLatitude and
			lon <= :rightLongitude
		"""
	)
	fun countInsideAndBetween(
			from: Long,
			to: Long,
			topLatitude: Double,
			rightLongitude: Double,
			bottomLatitude: Double,
			leftLongitude: Double
	): Int

	/**
	 * Get time of first and last record.
	 */
	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from location_data")
	fun range(): DateRange
}

