package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.DateRange

@Dao
interface LocationWifiCountDao : BaseDao<DatabaseLocationWifiCount> {
	@Query(
			"""
		SELECT lon, lat, count as weight
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
	): List<Database2DLocationWeightedMinimal>

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

	@Query(
			"""
		SELECT lon, lat, count as weight
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
	): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) FROM location_wifi_count")
	fun count(): Long

	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from location_wifi_count")
	fun range(): DateRange
}
