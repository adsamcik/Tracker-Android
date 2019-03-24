package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.signalcollector.database.data.DatabaseWifiData

@Dao
interface WifiDataDao {

	@Update(onConflict = OnConflictStrategy.IGNORE)
	fun insertWithUpdate(wifi: DatabaseWifiData): Int

	@Query("UPDATE wifi_data SET last_seen = :lastSeen, ssid = :ssid, capabilities = :capabilities, frequency = :frequency, location_id = CASE WHEN location_id IS NULL OR level < :level THEN :locationId ELSE location_id END, level = CASE WHEN level < :level THEN :level ELSE level END WHERE bssid = :bssid")
	fun update(locationId: Long?, bssid: String, ssid: String, capabilities: String, frequency: Int, lastSeen: Long, level: Int)

	@Query("SELECT * from wifi_data")
	fun getAll(): List<DatabaseWifiData>
}