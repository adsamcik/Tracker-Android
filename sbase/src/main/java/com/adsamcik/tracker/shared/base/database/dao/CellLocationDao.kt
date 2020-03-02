package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.location.Location2DWeighted
import com.adsamcik.tracker.shared.base.database.data.DatabaseCellLocation
import com.adsamcik.tracker.shared.base.database.data.DateRange
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted

@Dao
interface CellLocationDao : BaseDao<DatabaseCellLocation> {
	@Query("DELETE FROM cell_location")
	fun deleteAll()

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

	@Query("SELECT COUNT(*) from cell_location")
	fun count(): Long

	@Query("SELECT COUNT(*) FROM (SELECT COUNT(*) from cell_location GROUP BY mcc, mnc, cell_id)")
	fun uniqueCount(): Long

	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from cell_location")
	fun range(): DateRange
}

