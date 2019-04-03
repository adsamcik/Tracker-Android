package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.database.data.DatabaseLocation

@Dao
interface LocationDataDao {
	@Query("SELECT * from location_data")
	fun getAll(): List<DatabaseLocation>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	fun insert(locationData: DatabaseLocation): Long

	@Query("DELETE from location_data")
	fun deleteAll()

	@Query("SELECT * from location_data where time >= :from and time <= :to")
	fun getAllBetween(from: Long, to: Long): List<DatabaseLocation>

	@Query("SELECT * from location_data where time >= :from")
	fun getAllSince(from: Long): List<DatabaseLocation>

	@Query("SELECT lon, lat, hor_acc as weight FROM location_data where lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun getAllInside(topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT lon, lat, hor_acc as weight FROM location_data where time >= :from and time <= :to and lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun getAllInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) FROM location_data")
	fun count(): Long
}