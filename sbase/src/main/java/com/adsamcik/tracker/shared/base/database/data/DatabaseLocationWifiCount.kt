package com.adsamcik.tracker.common.database.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adsamcik.tracker.common.data.BaseLocation
import com.adsamcik.tracker.common.data.Location

@Entity(
		tableName = "location_wifi_count",
		indices = [Index("lon"), Index("lat"), Index("time")]
)
data class DatabaseLocationWifiCount(
		@PrimaryKey(autoGenerate = true)
		val id: Long = 0,
		val time: Long,
		@Embedded
		val location: BaseLocation,
		val count: Short
) {
	constructor(time: Long, location: Location, count: Short) : this(
			id = 0,
			time = time,
			location = BaseLocation(location),
			count = count
	)
}
