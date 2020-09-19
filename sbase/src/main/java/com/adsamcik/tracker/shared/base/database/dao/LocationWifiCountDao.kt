package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.DateRange
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

/**
 * Wi-Fi location data access object.
 */
@Dao
interface LocationWifiCountDao : BaseDao<DatabaseLocationWifiCount> {
	/**
	 * Get all Wi-Fi networks inside bounds grouped by location.
	 * Weight corresponds to number of networks at a given location.
	 *
	 * @param topLatitude Top bound (max latitude).
	 * @param leftLongitude Left bound (min longitude).
	 * @param bottomLatitude Bottom bound (min latitude).
	 * @param rightLongitude Right bound (max longitude).
	 *
	 * @return List of all Wi-Fi networks within bounds with count of networks as a weight.
	 */
	@Query(
			"""
		SELECT time, lon, lat, count as weight
		FROM location_wifi_count
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
	 * Get number of Wi-Fi networks inside bounds grouped by location.
	 *
	 * @param topLatitude Top bound (max latitude).
	 * @param leftLongitude Left bound (min longitude).
	 * @param bottomLatitude Bottom bound (min latitude).
	 * @param rightLongitude Right bound (max longitude).
	 *
	 * @return Integer representing number of Wi-Fi networks within bounds.
	 */
	@Query(
			"""
		SELECT COUNT(*)
		FROM location_wifi_count
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
	 * Get all Wi-Fi networks inside bounds and during set time period grouped by location.
	 * Weight corresponds to number of networks at a given location.
	 *
	 * @param topLatitude Top bound (max latitude).
	 * @param leftLongitude Left bound (min longitude).
	 * @param bottomLatitude Bottom bound (min latitude).
	 * @param rightLongitude Right bound (max longitude).
	 * @param from Start time constraint.
	 * @param to End time constraint.
	 *
	 * @return List of all Wi-Fi networks within constraints with count of networks as a weight.
	 */
	@Query(
			"""
		SELECT time, lon, lat, count as weight
		FROM location_wifi_count
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
	 * Get all Wi-Fi networks inside bounds and during set time period grouped by location.
	 * Weight corresponds to number of networks at a given location.
	 *
	 * @param from Start time constraint.
	 * @param to End time constraint.
	 *
	 * @return List of all Wi-Fi networks within constraints with count of networks as a weight.
	 */
	@Query(
			"""
		SELECT time, lon, lat, count as weight
		FROM location_wifi_count
		where
			time >= :from and
			time <= :to
		"""
	)
	fun getAllBetween(
			from: Long,
			to: Long
	): List<TimeLocation2DWeighted>

	/**
	 * Counts all Wi-Fi networks in database.
	 *
	 * @return Number of Wi-Fi networks.
	 */
	@Query("SELECT COUNT(*) FROM location_wifi_count")
	fun count(): Long

	/**
	 * Returns first and last collection times.
	 */
	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from location_wifi_count")
	fun range(): DateRange
}
