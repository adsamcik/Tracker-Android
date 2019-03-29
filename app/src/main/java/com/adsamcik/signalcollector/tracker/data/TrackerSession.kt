package com.adsamcik.signalcollector.tracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracking_session")
data class TrackerSession(var start: Long,
                          var end: Long,
                          var collections: Int,
                          @ColumnInfo(name = "distance")
                           var distanceInM: Float,
                          var steps: Int) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0

	constructor(start: Long) : this(start, -1, 0, 0f, 0)

	fun mergeWith(session: TrackerSession) {
		if (session.start > start) {
			end = session.end
		} else {
			start = session.start
		}

		collections += session.collections
		distanceInM += session.distanceInM
		steps += session.steps
	}
}