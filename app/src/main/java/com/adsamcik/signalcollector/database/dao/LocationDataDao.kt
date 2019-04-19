package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.database.data.DatabaseLocation
import com.adsamcik.signalcollector.tracker.data.collection.Location

@Dao
interface LocationDataDao : BaseDao<DatabaseLocation> {
	@Query("SELECT * from location_data")
	fun getAll(): List<DatabaseLocation>

	@Query("DELETE from location_data")
	fun deleteAll()

	@Query("SELECT * from location_data where time >= :from and time <= :to")
	fun getAllBetween(from: Long, to: Long): List<DatabaseLocation>

	@Query("SELECT * from location_data where time >= :from")
	fun getAllSince(from: Long): List<DatabaseLocation>

	@Query("SELECT lon, lat, hor_acc as weight FROM location_data where lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun getAllInside(topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) FROM location_data where time >= :from and time <= :to and lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun countInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): Int

	@Query("SELECT lon, lat, hor_acc as weight FROM location_data where time >= :from and time <= :to and lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun getAllInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) FROM location_data")
	fun count(): Long


	@Transaction
	fun new(list: List<Pair<Double, Double>>, time: Long, accuracy: Double): List<Pair<Double, Double>> {
		return list.filter {
			val halfAccuracyLatitude = Location.latitudeAccuracy(accuracy) / 2.0
			val halfAccuracyLongitude = Location.longitudeAccuracy(accuracy, it.first) / 2.0
			countInsideAndBetween(0,
					time,
					it.first + halfAccuracyLatitude,
					it.second + halfAccuracyLongitude,
					it.first - halfAccuracyLatitude,
					it.second - halfAccuracyLongitude) == 0
		}
	}
}