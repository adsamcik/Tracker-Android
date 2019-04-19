package com.adsamcik.signalcollector.tracker.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracker_session")
data class MutableTrackerSession(
		override var start: Long,
		override var end: Long,
		override var collections: Int,
		@ColumnInfo(name = "distance")
		override var distanceInM: Float,
		@ColumnInfo(name = "distance_on_foot")
		override var distanceOnFootInM: Float,
		@ColumnInfo(name = "distance_in_vehicle")
		override var distanceInVehicleInM: Float,
		override var steps: Int) : TrackerSession {

	@PrimaryKey(autoGenerate = true)
	override var id: Long = 0

	constructor(start: Long) : this(start, start, 0, 0f, 0f, 0f, 0)
}