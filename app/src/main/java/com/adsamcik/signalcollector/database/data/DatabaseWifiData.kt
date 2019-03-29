package com.adsamcik.signalcollector.database.data

import androidx.room.*
import androidx.room.ForeignKey.NO_ACTION
import androidx.room.ForeignKey.SET_NULL
import com.adsamcik.signalcollector.tracker.data.WifiInfo

@Entity(tableName = "wifi_data", foreignKeys = [ForeignKey(entity = DatabaseLocation::class,
		parentColumns = ["id"],
		childColumns = ["location_id"],
		onDelete = SET_NULL,
		onUpdate = NO_ACTION)])
data class DatabaseWifiData(
		@ColumnInfo(name = "location_id", index = true) val locationId: Long?,
		@ColumnInfo(name = "first_seen") var firstSeen: Long,
		@ColumnInfo(name = "last_seen") var lastSeen: Long,
		@Embedded val wifiInfo: WifiInfo) {
	@PrimaryKey(autoGenerate = false)
	var id: String = wifiInfo.BSSID
}

