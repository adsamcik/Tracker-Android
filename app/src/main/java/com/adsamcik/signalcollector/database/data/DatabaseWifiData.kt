package com.adsamcik.signalcollector.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.tracker.data.collection.Location
import com.adsamcik.signalcollector.tracker.data.collection.WifiInfo

@Entity(tableName = "wifi_data", indices = [Index("longitude"), Index("latitude"), Index("last_seen")])
data class DatabaseWifiData(
		@PrimaryKey @ColumnInfo(name = "bssid") var BSSID: String,
		val longitude: Double,
		val latitude: Double,
		val altitude: Double?,
		@ColumnInfo(name = "first_seen") var firstSeen: Long,
		@ColumnInfo(name = "last_seen") var lastSeen: Long,
		@ColumnInfo(name = "ssid") var SSID: String,
		var capabilities: String,
		var frequency: Int = 0,
		var level: Int = 0) {

	constructor(location: Location, wifiData: WifiInfo) : this(wifiData.bssid, location.longitude, location.latitude, location.altitude, location.time, location.time, wifiData.ssid, wifiData.capabilities, wifiData.frequency, wifiData.level)
}
