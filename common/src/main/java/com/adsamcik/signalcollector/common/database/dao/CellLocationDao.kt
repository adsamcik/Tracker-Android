package com.adsamcik.signalcollector.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.signalcollector.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.common.database.data.DatabaseCellLocation
import com.adsamcik.signalcollector.common.database.data.DateRange

@Dao
interface CellLocationDao : BaseDao<DatabaseCellLocation> {
	@Query("DELETE FROM cell_location")
	fun deleteAll()

	@Query("""
		SELECT lat, lon, type as weight FROM cell_location
		WHERE lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude""")
	fun getAllInside(topLatitude: Double,
	                 rightLongitude: Double,
	                 bottomLatitude: Double,
	                 leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("""SELECT lat, lon, type as weight FROM cell_location
			WHERE time >= :from and time <= :to and lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude""")
	fun getAllInsideAndBetween(from: Long,
	                           to: Long,
	                           topLatitude: Double,
	                           rightLongitude: Double,
	                           bottomLatitude: Double,
	                           leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) from cell_location")
	fun count(): Long

	@Query("SELECT COUNT(*) from cell_location GROUP BY mcc, mnc, cell_id")
	fun uniqueCount(): Long

	@Query("SELECT MIN(time) as start, MAX(time) as endInclusive from cell_location")
	fun range(): DateRange
}
