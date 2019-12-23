package com.adsamcik.tracker.common.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.common.data.ActivityInfo
import com.adsamcik.tracker.common.data.Location

@Entity(tableName = "location_data", indices = [Index("lat"), Index("lon"), Index("time")])
data class DatabaseLocation(
		@Embedded val location: Location,
		@Embedded val activityInfo: ActivityInfo
) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0

	val latitude: Double get() = location.latitude

	val longitude: Double get() = location.longitude

	val altitude: Double? get() = location.altitude

	val time: Long get() = location.time
}

