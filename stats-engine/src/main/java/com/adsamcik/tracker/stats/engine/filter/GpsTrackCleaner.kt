package com.adsamcik.tracker.stats.engine.filter

import java.util.Arrays

/**
 * A GPS location point for cleaning.
 */
data class GpsPoint(
	val timeMs: Long,
	val latitudeDeg: Double,
	val longitudeDeg: Double,
	val altitudeM: Float?,
	val speedMps: Float?,
	val horizontalAccuracyM: Float?,
	val verticalAccuracyM: Float?
)

/**
 * A segment of cleaned GPS points (contiguous, no large time gaps).
 */
data class CleanedSegment(
	val points: List<GpsPoint>
)

/**
 * Multi-stage GPS track cleaning pipeline.
 *
 * Pipeline:
 * 1. Sort by time, remove duplicates
 * 2. Accuracy gate: drop points with poor accuracy
 * 3. Spike removal: remove impossible speed/acceleration
 * 4. Median filter: smooth remaining jitter
 * 5. Segment by time gaps
 *
 * Pure function, no Android dependencies.
 */
object GpsTrackCleaner {

	/**
	 * Clean a GPS track and split into segments.
	 */
	fun clean(
		points: List<GpsPoint>,
		config: GpsCleaningConfig = GpsCleaningConfig()
	): List<CleanedSegment> {
		if (points.isEmpty()) return emptyList()

		val validated = ArrayList<GpsPoint>(points.size)
		for (point in points) {
			if (isValidPoint(point)) {
				validated.add(point)
			}
		}
		if (validated.isEmpty()) return emptyList()

		validated.sortBy { it.timeMs }

		val accuracyFiltered = ArrayList<GpsPoint>(validated.size)
		var hasLastTime = false
		var lastTimeMs = Long.MIN_VALUE
		for (point in validated) {
			if (hasLastTime && point.timeMs == lastTimeMs) {
				continue
			}
			hasLastTime = true
			lastTimeMs = point.timeMs
			val accuracy = point.horizontalAccuracyM
			if (accuracy == null || accuracy <= config.accuracyGateM) {
				accuracyFiltered.add(point)
			}
		}

		if (accuracyFiltered.isEmpty()) return emptyList()

		val despiked = removeSpikesBySpeed(accuracyFiltered, config.maxSpeedMps)

		if (despiked.isEmpty()) return emptyList()

		val smoothed = medianFilterPosition(despiked, config.positionMedianWindow)

		return segmentByGaps(smoothed, config.segmentGapMs, config.minSegmentPoints)
	}

	/**
	 * Remove points that would require impossible speed to reach from the previous point.
	 */
	internal fun removeSpikesBySpeed(points: List<GpsPoint>, maxSpeedMps: Float): List<GpsPoint> {
		if (points.size < 2) return points
		val result = ArrayList<GpsPoint>(points.size)
		result.add(points[0])
		for (i in 1 until points.size) {
			val prev = result.last()
			val curr = points[i]
			val dt = (curr.timeMs - prev.timeMs) / 1000.0
			if (dt <= 0) continue
			val dist = haversineDistance(
				prev.latitudeDeg, prev.longitudeDeg,
				curr.latitudeDeg, curr.longitudeDeg
			)
			val speed = dist / dt
			if (speed <= maxSpeedMps) {
				result.add(curr)
			}
		}
		return result
	}

	/**
	 * Apply median filter to lat/lon to reduce jitter.
	 * Altitude is filtered separately with its own window.
	 */
	internal fun medianFilterPosition(points: List<GpsPoint>, windowSize: Int): List<GpsPoint> {
		if (windowSize <= 1 || points.size <= windowSize) return points
		val halfWindow = windowSize / 2
		val maxWindowLength = halfWindow * 2 + 1
		val latitudes = DoubleArray(maxWindowLength)
		val longitudes = DoubleArray(maxWindowLength)
		val altitudes = FloatArray(maxWindowLength)
		val result = ArrayList<GpsPoint>(points.size)
		for (index in points.indices) {
			val point = points[index]
			val start = (index - halfWindow).coerceAtLeast(0)
			val end = (index + halfWindow).coerceAtMost(points.lastIndex)
			val windowLength = end - start + 1
			var altitudeCount = 0
			for (windowIndex in 0 until windowLength) {
				val windowPoint = points[start + windowIndex]
				latitudes[windowIndex] = windowPoint.latitudeDeg
				longitudes[windowIndex] = windowPoint.longitudeDeg
				val altitude = windowPoint.altitudeM
				if (altitude != null) {
					altitudes[altitudeCount++] = altitude
				}
			}

			Arrays.sort(latitudes, 0, windowLength)
			Arrays.sort(longitudes, 0, windowLength)
			val medianLat = latitudes[windowLength / 2]
			val medianLon = longitudes[windowLength / 2]
			val medianAlt = if (point.altitudeM != null && altitudeCount > 0) {
				Arrays.sort(altitudes, 0, altitudeCount)
				altitudes[altitudeCount / 2]
			} else {
				null
			}
			result.add(point.copy(
				latitudeDeg = medianLat,
				longitudeDeg = medianLon,
				altitudeM = medianAlt
			))
		}
		return result
	}

	/**
	 * Split points into segments wherever there's a time gap exceeding threshold.
	 */
	internal fun segmentByGaps(
		points: List<GpsPoint>,
		gapMs: Long,
		minPoints: Int
	): List<CleanedSegment> {
		if (points.isEmpty()) return emptyList()
		val segments = ArrayList<CleanedSegment>()
		var segmentStart = 0
		for (i in 1 until points.size) {
			if (points[i].timeMs - points[i - 1].timeMs > gapMs) {
				addSegmentIfLargeEnough(points, segmentStart, i, minPoints, segments)
				segmentStart = i
			}
		}
		addSegmentIfLargeEnough(points, segmentStart, points.size, minPoints, segments)
		return segments
	}

	private fun addSegmentIfLargeEnough(
		points: List<GpsPoint>,
		startInclusive: Int,
		endExclusive: Int,
		minPoints: Int,
		segments: MutableList<CleanedSegment>
	) {
		if (endExclusive - startInclusive >= minPoints) {
			segments.add(CleanedSegment(ArrayList(points.subList(startInclusive, endExclusive))))
		}
	}

	/**
	 * Returns true if the point has finite coordinates within valid ranges and positive time.
	 */
	internal fun isValidPoint(point: GpsPoint): Boolean {
		return point.timeMs > 0 &&
				point.latitudeDeg.isFinite() &&
				point.longitudeDeg.isFinite() &&
				point.latitudeDeg in -90.0..90.0 &&
				point.longitudeDeg in -180.0..180.0
	}

	/**
	 * Haversine distance in meters between two lat/lon points.
	 */
	internal fun haversineDistance(
		lat1: Double, lon1: Double,
		lat2: Double, lon2: Double
	): Double {
		val earthRadius = 6_371_000.0
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val a = kotlin.math.sin(dLat / 2).let { it * it } +
				kotlin.math.cos(Math.toRadians(lat1)) *
				kotlin.math.cos(Math.toRadians(lat2)) *
				kotlin.math.sin(dLon / 2).let { it * it }
		val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
		return earthRadius * c
	}
}
