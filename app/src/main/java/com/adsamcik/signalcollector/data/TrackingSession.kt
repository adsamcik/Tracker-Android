package com.adsamcik.signalcollector.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class TrackingSession(var start: Long,
                           var end: Long,
                           var collections: Int,
                           @ColumnInfo(name = "distance")
                           var distanceInM: Float,
                           var steps: Int) {
	@PrimaryKey(autoGenerate = true)
	var id: Int = 0

	constructor(start: Long) : this(start, -1, 0, 0f, 0)
}