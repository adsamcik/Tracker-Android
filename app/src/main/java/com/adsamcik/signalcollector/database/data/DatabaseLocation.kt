package com.adsamcik.signalcollector.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.tracker.data.Location
import com.adsamcik.signalcollector.activity.ActivityInfo

@Entity(tableName = "location_data", indices = [Index("lat"), Index("lon"), Index("time")])
data class DatabaseLocation(@Embedded val location: Location, @Embedded val activityInfo: ActivityInfo) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0

	val latitude get() = location.latitude

	val longitude get() = location.longitude

	val altitude get() = location.altitude
}