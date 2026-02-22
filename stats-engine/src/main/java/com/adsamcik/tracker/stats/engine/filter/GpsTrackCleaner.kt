package com.adsamcik.tracker.stats.engine.filter

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

		val validated = points.filter { isValidPoint(it) }
		if (validated.isEmpty()) return emptyList()

		val sorted = validated
			.sortedBy { it.timeMs }
			.distinctBy { it.timeMs }

		val accuracyFiltered = sorted.filter { point ->
			val acc = point.horizontalAccuracyM
			acc == null || acc <= config.accuracyGateM
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
		val result = mutableListOf(points[0])
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
		if (points.size <= windowSize) return points
		val halfWindow = windowSize / 2
		return points.mapIndexed { index, point ->
			val start = (index - halfWindow).coerceAtLeast(0)
			val end = (index + halfWindow).coerceAtMost(points.lastIndex)
			val window = points.subList(start, end + 1)
			val medianLat = window.map { it.latitudeDeg }.sorted()[window.size / 2]
			val medianLon = window.map { it.longitudeDeg }.sorted()[window.size / 2]
			val medianAlt = point.altitudeM?.let {
				window.mapNotNull { w -> w.altitudeM }.sorted().let { alts ->
					if (alts.isNotEmpty()) alts[alts.size / 2] else null
				}
			}
			point.copy(
				latitudeDeg = medianLat,
				longitudeDeg = medianLon,
				altitudeM = medianAlt
			)
		}
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
		val segments = mutableListOf<MutableList<GpsPoint>>()
		var current = mutableListOf(points[0])
		for (i in 1 until points.size) {
			if (points[i].timeMs - points[i - 1].timeMs > gapMs) {
				segments.add(current)
				current = mutableListOf()
			}
			current.add(points[i])
		}
		segments.add(current)
		return segments
			.filter { it.size >= minPoints }
			.map { CleanedSegment(it) }
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
