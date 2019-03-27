package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.database.data.DatabaseWifiData

@Dao
interface WifiDataDao {

	@Update(onConflict = OnConflictStrategy.IGNORE)
	fun insertWithUpdate(wifi: DatabaseWifiData): Int

	@Query("UPDATE wifi_data SET last_seen = :lastSeen, ssid = :ssid, capabilities = :capabilities, frequency = :frequency, location_id = CASE WHEN location_id IS NULL OR level < :level THEN :locationId ELSE location_id END, level = CASE WHEN level < :level THEN :level ELSE level END WHERE bssid = :bssid")
	fun update(locationId: Long?, bssid: String, ssid: String, capabilities: String, frequency: Int, lastSeen: Long, level: Int)

	@Query("SELECT * from wifi_data")
	fun getAll(): List<DatabaseWifiData>

	@Query("SELECT l.id, lat, lon, hor_acc as weight FROM location_data l INNER JOIN wifi_data w ON w.location_id == l.id WHERE lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun getAllInside(topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT l.id, lat, lon, hor_acc as weight FROM location_data l INNER JOIN wifi_data w ON w.location_id == l.id WHERE time >= :from and time <= :to and lat >= :bottomLatitude and lon >= :leftLongitude and lat <= :topLatitude and lon <= :rightLongitude")
	fun getAllInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) from wifi_data")
	fun count(): Long

}