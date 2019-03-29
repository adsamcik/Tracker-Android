package com.adsamcik.signalcollector.database.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.tracker.data.WifiInfo

@Entity(tableName = "wifi_data")
data class DatabaseWifiData(
		val longitude: Double,
		val latitude: Double,
		val altitude: Double?,
		@ColumnInfo(name = "first_seen") var firstSeen: Long,
		@ColumnInfo(name = "last_seen") var lastSeen: Long,
		@Embedded val wifiInfo: WifiInfo) {
	@PrimaryKey(autoGenerate = false)
	var id: String = wifiInfo.BSSID
}

