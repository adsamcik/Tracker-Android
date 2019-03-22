package com.adsamcik.signalcollector.database.data

import androidx.room.*
import androidx.room.ForeignKey.NO_ACTION
import androidx.room.ForeignKey.SET_NULL
import com.adsamcik.signalcollector.data.WifiInfo

@Entity(tableName = "wifi_data", foreignKeys = [ForeignKey(entity = DatabaseLocation::class,
		parentColumns = ["id"],
		childColumns = ["location_id"],
		onDelete = SET_NULL,
		onUpdate = NO_ACTION)])
data class DatabaseWifiData(
		@ColumnInfo(name = "location_id") val locationId: Long?,
		@Embedded val wifiInfo: WifiInfo) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0
}