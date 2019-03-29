package com.adsamcik.signalcollector.database.dao

import androidx.room.*
import com.adsamcik.signalcollector.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.database.data.DatabaseWifiData

@Dao
interface WifiDataDao {

	@Update(onConflict = OnConflictStrategy.IGNORE)
	fun insertWithUpdate(data: DatabaseWifiData): Int

	@Query("UPDATE wifi_data SET longitude = :longitude, latitude = :latitude, altitude = :altitude, level = :level WHERE bssid = :bssid AND level < :level")
	fun updateSignalStrength(bssid: String, longitude: Double, latitude: Double, altitude: Double?, level: Int)

	@Query("UPDATE wifi_data SET last_seen = :lastSeen, ssid = :ssid, capabilities = :capabilities, frequency = :frequency WHERE bssid = :bssid")
	fun updateData(bssid: String, ssid: String, capabilities: String, frequency: Int, lastSeen: Long)

	@Transaction
	fun upsert(wifiData: DatabaseWifiData) {
		if (insertWithUpdate(wifiData) == 0) {
			updateSignalStrength(wifiData.wifiInfo.BSSID, wifiData.longitude, wifiData.latitude, wifiData.altitude, wifiData.wifiInfo.level)
			updateData(wifiData.wifiInfo.BSSID, wifiData.wifiInfo.SSID, wifiData.wifiInfo.capabilities, wifiData.wifiInfo.frequency, wifiData.lastSeen)
		}
	}

	@Query("SELECT * from wifi_data")
	fun getAll(): List<DatabaseWifiData>

	@Query("SELECT id, latitude as lat, longitude as lon, COUNT(*) as weight FROM wifi_data WHERE latitude >= :bottomLatitude and longitude >= :leftLongitude and latitude <= :topLatitude and longitude <= :rightLongitude")
	fun getAllInside(topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT id, latitude as lat, longitude as lon, COUNT(*) as weight FROM wifi_data WHERE last_seen >= :from and last_seen <= :to and latitude >= :bottomLatitude and longitude >= :leftLongitude and latitude <= :topLatitude and longitude <= :rightLongitude")
	fun getAllInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) from wifi_data")
	fun count(): Long

}