package com.adsamcik.signalcollector.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracker_session")
data class TrackerSession(var start: Long,
                          var end: Long,
                          var collections: Int,
                          @ColumnInfo(name = "distance")
                          var distanceInM: Float,
                          @ColumnInfo(name = "distance_on_foot")
                          var distanceOnFootInM: Float,
                          @ColumnInfo(name = "distance_in_vehicle")
                          var distanceInVehicleInM: Float,
                          var steps: Int) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0

	constructor(start: Long) : this(start, start, 0, 0f, 0f, 0f, 0)
}