package com.adsamcik.tracker.statistics.data

import com.adsamcik.tracker.shared.base.data.Location
import com.goebl.simplify.Point3DExtractor

/**
 * Extracts X, Y and Z coordinates from location.
 */
class LocationExtractor : Point3DExtractor<Location> {
	override fun getX(point: Location): Double {
		return point.longitude * MULTIPLICATION_CONSTANT
	}

	override fun getY(point: Location): Double {
		return point.latitude * MULTIPLICATION_CONSTANT
	}

	override fun getZ(point: Location): Double {
		return (point.altitude ?: 0.0) * MULTIPLICATION_CONSTANT
	}

	companion object {
		private const val MULTIPLICATION_CONSTANT = 1000000.0
	}
}
