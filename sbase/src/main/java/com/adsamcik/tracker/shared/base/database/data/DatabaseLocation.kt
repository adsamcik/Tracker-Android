package com.adsamcik.tracker.shared.base.database.data

import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location

/**
 * Legacy location DTO retained as a plain data class for bridging code that
 * needs both [Location] and [ActivityInfo] per point (e.g. activity recognizers).
 *
 * **Not a Room entity.** New code should use [LocationSample] for persistence.
 */
data class DatabaseLocation(
		val location: Location,
		val activityInfo: ActivityInfo
) {
	val latitude: Double get() = location.latitude

	val longitude: Double get() = location.longitude

	val altitude: Double? get() = location.altitude

	val time: Long get() = location.time
}

