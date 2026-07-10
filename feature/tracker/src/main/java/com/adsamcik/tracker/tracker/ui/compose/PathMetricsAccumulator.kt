package com.adsamcik.tracker.tracker.ui.compose

import com.adsamcik.tracker.shared.model.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PathMetrics(
	val distanceMeters: Double = 0.0,
	val movingAverageSpeedMps: Double = 0.0,
)

internal class PathMetricsAccumulator(
	private val distanceBetween: (Location, Location) -> Double = Location::distanceFlatMeters,
) {
	private var previousPath: List<Location>? = null
	private var processedSize = 0
	private var distanceMeters = 0.0
	private var movingDistanceMeters = 0.0
	private var movingDurationSeconds = 0.0

	fun update(path: List<Location>?): PathMetrics {
		if (path.isNullOrEmpty()) {
			reset()
			return PathMetrics()
		}

		val previous = previousPath
		val isAppendOnly = previous != null &&
			processedSize == previous.size &&
			path.size >= processedSize &&
			path.first() == previous.first() &&
			path[processedSize - 1] == previous.last()

		val startIndex = if (isAppendOnly) {
			processedSize.coerceAtLeast(1)
		} else {
			resetMetrics()
			1
		}

		for (index in startIndex until path.size) {
			val start = path[index - 1]
			val end = path[index]
			val segmentDistanceMeters = distanceBetween(start, end).coerceAtLeast(0.0)
			distanceMeters += segmentDistanceMeters

			val deltaMillis = end.time - start.time
			if (deltaMillis > 0L && segmentDistanceMeters > 0.0) {
				movingDistanceMeters += segmentDistanceMeters
				movingDurationSeconds += deltaMillis.toDouble() / 1000.0
			}
		}

		previousPath = path
		processedSize = path.size
		return PathMetrics(
			distanceMeters = distanceMeters,
			movingAverageSpeedMps = if (movingDurationSeconds > 0.0) {
				movingDistanceMeters / movingDurationSeconds
			} else {
				0.0
			},
		)
	}

	private fun reset() {
		previousPath = null
		processedSize = 0
		resetMetrics()
	}

	private fun resetMetrics() {
		distanceMeters = 0.0
		movingDistanceMeters = 0.0
		movingDurationSeconds = 0.0
	}
}

private const val EARTH_CIRCUMFERENCE_METERS = 40_075_000.0

private fun Location.distanceFlatMeters(other: Location): Double {
	val lat1Rad = Math.toRadians(latitude)
	val lat2Rad = Math.toRadians(other.latitude)
	val latDistance = Math.toRadians(other.latitude - latitude)
	val lonDistance = Math.toRadians(other.longitude - longitude)
	val sinLatDistance = sin(latDistance / 2)
	val sinLonDistance = sin(lonDistance / 2)
	val a = sinLatDistance * sinLatDistance +
		cos(lat1Rad) * cos(lat2Rad) * sinLonDistance * sinLonDistance
	val c = 2 * atan2(sqrt(a), sqrt(1 - a))
	return (EARTH_CIRCUMFERENCE_METERS / (2 * Math.PI)) * c
}
