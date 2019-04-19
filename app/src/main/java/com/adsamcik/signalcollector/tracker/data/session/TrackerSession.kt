package com.adsamcik.signalcollector.tracker.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracker_session")
open class TrackerSession(
		id: Long = 0,
		start: Long = 0,
		end: Long = 0,
		collections: Int = 0,
		distanceInM: Float = 0f,
		distanceOnFootInM: Float = 0f,
		distanceInVehicleInM: Float = 0f,
		steps: Int = 0) {

	@PrimaryKey(autoGenerate = true)
	open var id: Long = id
		protected set

	open var start: Long = start
		protected set

	open var end: Long = end
		protected set

	open var collections: Int = collections
		protected set

	@ColumnInfo(name = "distance")
	open var distanceInM: Float = distanceInM
		protected set

	@ColumnInfo(name = "distance_on_foot")
	open var distanceOnFootInM: Float = distanceOnFootInM
		protected set

	@ColumnInfo(name = "distance_in_vehicle")
	open var distanceInVehicleInM: Float = distanceInVehicleInM
		protected set

	open var steps: Int = steps
		protected set
}