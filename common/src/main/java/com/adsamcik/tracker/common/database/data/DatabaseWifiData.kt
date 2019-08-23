package com.adsamcik.tracker.common.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.common.data.Location
import com.adsamcik.tracker.common.data.WifiInfo

@Entity(
		tableName = "wifi_data",
		indices = [Index("longitude"), Index("latitude"), Index("last_seen")]
)
data class DatabaseWifiData(
		@PrimaryKey @ColumnInfo(name = "bssid") var BSSID: String,
		val longitude: Double?,
		val latitude: Double?,
		val altitude: Double?,
		@ColumnInfo(name = "first_seen") var firstSeen: Long,
		@ColumnInfo(name = "last_seen") var lastSeen: Long,
		@ColumnInfo(name = "ssid") var SSID: String,
		var capabilities: String,
		var frequency: Int = 0,
		var level: Int = 0
) {

	constructor(time: Long, wifiData: WifiInfo, location: Location) : this(
			wifiData.bssid,
			location.longitude,
			location.latitude,
			location.altitude,
			time,
			time,
			wifiData.ssid,
			wifiData.capabilities,
			wifiData.frequency,
			wifiData.level
	)

	constructor(time: Long, wifiData: WifiInfo) : this(
			wifiData.bssid, null, null, null, time, time, wifiData.ssid,
			wifiData.capabilities, wifiData.frequency, wifiData.level
	)
}

