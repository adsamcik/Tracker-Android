package com.adsamcik.tracker.stats.api.value

import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7

/** GPS coordinate pair. Both components validated at construction. */
data class CoordinateE7(val lat: LatE7, val lon: LonE7) {
	/** Canonical spatial identity without destructively rewriting a legacy raw value. */
	fun canonicalSpatial(): CoordinateE7 {
		val checked = CheckedCoordinateE7.requireE7(lat.raw.toLong(), lon.raw.toLong())
		return CoordinateE7(LatE7(checked.latitudeE7), LonE7(checked.longitudeE7))
	}

	companion object {
		fun fromDegrees(latitudeDegrees: Double, longitudeDegrees: Double): CoordinateE7 {
			val checked = CheckedCoordinateE7.requireDegrees(latitudeDegrees, longitudeDegrees)
			return CoordinateE7(LatE7(checked.latitudeE7), LonE7(checked.longitudeE7))
		}
	}
}
