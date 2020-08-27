package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.data.Location
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
	 * Get all location data.
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from location_data")
	fun getAll(): List<DatabaseLocation>

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
	 * Get all location data more recent that [from].
	 */
	@RewriteQueriesToDropUnusedColumns
	@Query("SELECT * from location_data where time >= :from")
	fun getAllSince(from: Long): List<DatabaseLocation>

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
	 * Count all location records in database.
	 */
	@Query("SELECT COUNT(*) FROM location_data")
	fun count(): Long

	/**
	 * Get time of first and last record.
	 */
	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from location_data")
	fun range(): DateRange

	/**
	 * Get all new locations since time.
	 * It is considered new location if it is first record within [accuracy].
	 */
	@Transaction
	fun newLocations(
			list: List<Pair<Double, Double>>,
			time: Long,
			accuracy: Double
	): List<Pair<Double, Double>> {
		return list.filter {
			val halfAccuracyLatitude = Location.latitudeAccuracy(accuracy) / 2.0
			val halfAccuracyLongitude = Location.longitudeAccuracy(accuracy, it.first) / 2.0
			countInsideAndBetween(
					0,
					time,
					it.first + halfAccuracyLatitude,
					it.second + halfAccuracyLongitude,
					it.first - halfAccuracyLatitude,
					it.second - halfAccuracyLongitude
			) == 0
		}
	}
}

