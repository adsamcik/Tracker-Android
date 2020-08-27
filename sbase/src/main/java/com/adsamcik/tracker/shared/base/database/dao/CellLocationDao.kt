package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.shared.base.database.data.DateRange
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

/**
 * Cell location data access object.
 */
@Dao
interface CellLocationDao : BaseDao<DatabaseCellLocation> {
	/**
	 * Delete all cell locations from database
	 */
	@Query("DELETE FROM cell_location")
	fun deleteAll()

	/**
	 * Get all cell locations inside area restriction.
	 */
	@Query(
			"""
		SELECT time, lat, lon, type as weight FROM cell_location
		WHERE lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude"""
	)
	fun getAllInside(
			topLatitude: Double,
			rightLongitude: Double,
			bottomLatitude: Double,
			leftLongitude: Double
	): List<TimeLocation2DWeighted>

	/**
	 * Get all cell locations inside area and time restrictions.
	 */
	@Query(
			"""
				SELECT time, lat, lon, type as weight
				FROM cell_location
				WHERE
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
	 * Count all cell locations.
	 */
	@Query("SELECT COUNT(*) from cell_location")
	fun count(): Long

	/**
	 * Count all unique cell locations.
	 * Unique cell location is identified by grouping with operator identification (mcc, mnc) and cell id.
	 */
	@Query("SELECT COUNT(*) FROM (SELECT COUNT(*) from cell_location GROUP BY mcc, mnc, cell_id)")
	fun uniqueCount(): Long

	/**
	 * Count all unique cell locations with time restriction.
	 * Unique cell location is identified by grouping with operator identification (mcc, mnc) and cell id.
	 */
	@Query(
			"""
		SELECT COUNT(*) FROM (
			SELECT COUNT(*) FROM cell_location 
			WHERE time >= :from AND time <= :to
			GROUP BY mcc, mnc, cell_id
		)
	"""
	)
	fun uniqueCount(from: Long, to: Long): Long

	/***
	 * Get time of first and last cell location.
	 */
	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from cell_location")
	fun range(): DateRange
}

