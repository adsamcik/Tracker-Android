package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location

/**
 * Database location object containing various location data and activity info.
 */
data class DatabaseLocation(
		val location: Location,
		val activityInfo: ActivityInfo
) {
	var id: Int = 0

	val latitude: Double get() = location.latitude

	val longitude: Double get() = location.longitude

	val altitude: Double? get() = location.altitude

	val time: Long get() = location.time
}
