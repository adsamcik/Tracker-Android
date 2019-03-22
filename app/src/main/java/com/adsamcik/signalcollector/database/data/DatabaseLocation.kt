package com.adsamcik.signalcollector.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.signalcollector.data.Location
import com.adsamcik.signalcollector.utility.ActivityInfo

@Entity(tableName = "location_data")
data class DatabaseLocation(@Embedded val location: Location, @Embedded val activityInfo: ActivityInfo) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0

	val latitude get() = location.latitude

	val longitude get() = location.longitude

	val altitude get() = location.altitude
}