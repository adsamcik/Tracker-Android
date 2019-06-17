package com.adsamcik.signalcollector.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.signalcollector.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.common.database.data.DatabaseWifiData

@Dao
interface WifiDataDao : BaseDao<DatabaseWifiData> {

	@Query("UPDATE wifi_data SET longitude = :longitude, latitude = :latitude, altitude = :altitude, level = :level WHERE bssid = :bssid AND level < :level")
	fun updateSignalStrength(bssid: String, longitude: Double, latitude: Double, altitude: Double?, level: Int)

	@Query("UPDATE wifi_data SET last_seen = :lastSeen, ssid = :ssid, capabilities = :capabilities, frequency = :frequency WHERE bssid = :bssid")
	fun updateData(bssid: String, ssid: String, capabilities: String, frequency: Int, lastSeen: Long)

	@Transaction
	fun upsert(objList: Collection<DatabaseWifiData>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		updateList.forEach {
			updateSignalStrength(it.BSSID, it.longitude, it.latitude, it.altitude, it.level)
			updateData(it.BSSID, it.SSID, it.capabilities, it.frequency, it.lastSeen)
		}
	}

	@Query("SELECT * from wifi_data")
	fun getAll(): List<DatabaseWifiData>

	@Query("SELECT latitude as lat, longitude as lon, COUNT(*) as weight FROM wifi_data WHERE latitude >= :bottomLatitude and longitude >= :leftLongitude and latitude <= :topLatitude and longitude <= :rightLongitude")
	fun getAllInside(topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT latitude as lat, longitude as lon, COUNT(*) as weight FROM wifi_data WHERE last_seen >= :from and last_seen <= :to and latitude >= :bottomLatitude and longitude >= :leftLongitude and latitude <= :topLatitude and longitude <= :rightLongitude")
	fun getAllInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("SELECT COUNT(*) from wifi_data")
	fun count(): Long
}