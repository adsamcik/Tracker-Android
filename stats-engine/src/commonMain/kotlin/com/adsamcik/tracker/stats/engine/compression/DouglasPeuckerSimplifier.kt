package com.adsamcik.tracker.stats.engine.compression

import com.adsamcik.tracker.stats.engine.algorithm.DouglasPeucker

/**
 * A geographic coordinate pair using decimal degrees.
 */
data class LatLng(val lat: Double, val lng: Double)

/**
 * Douglas-Peucker polyline simplification with geographic awareness.
 *
 * Wraps [DouglasPeucker] to accept [LatLng] coordinates with an
 * epsilon tolerance specified in meters. Converts the meter-based
 * tolerance to approximate degrees internally.
 *
 * Typical usage: reduce GPS track point count by 80-95% while
 * preserving route shape within the specified tolerance.
 */
object DouglasPeuckerSimplifier {

	/**
	 * Approximate meters per degree of latitude.
	 * Accurate within ~0.5% between 0-60 degrees latitude.
	 */
	private const val METERS_PER_DEGREE = 111_320.0

	/**
	 * Simplify a list of geographic coordinates.
	 *
	 * @param points Input polyline as [LatLng] pairs
	 * @param epsilonMeters Maximum perpendicular distance tolerance in meters.
	 *   Points closer than this to the simplified line are removed.
	 *   Typical values: 5-20m for walking, 10-50m for driving.
	 * @return Simplified polyline (subset of input points preserving order)
	 * @throws IllegalArgumentException if [epsilonMeters] is not positive
	 */
	fun simplify(points: List<LatLng>, epsilonMeters: Double): List<LatLng> {
		require(epsilonMeters > 0.0) { "Epsilon must be positive, was $epsilonMeters" }
		if (points.size <= 2) return points.toList()

		val converted = points.map { DouglasPeucker.Point(it.lat, it.lng) }
		val epsilonDeg = epsilonMeters / METERS_PER_DEGREE
		val simplified = DouglasPeucker.simplify(converted, epsilonDeg)

		return simplified.map { LatLng(it.x, it.y) }
	}

	/**
	 * Simplify and return indices of retained points.
	 *
	 * Useful when the caller needs to correlate simplified points
	 * with metadata (timestamps, accuracy) from the original list.
	 *
	 * @param points Input polyline
	 * @param epsilonMeters Tolerance in meters
	 * @return Indices into [points] of the retained points
	 */
	fun simplifyIndices(points: List<LatLng>, epsilonMeters: Double): List<Int> {
		require(epsilonMeters > 0.0) { "Epsilon must be positive, was $epsilonMeters" }
		if (points.size <= 2) return points.indices.toList()

		val latE7 = IntArray(points.size) { (points[it].lat * 1e7).toInt() }
		val lonE7 = IntArray(points.size) { (points[it].lng * 1e7).toInt() }

		return DouglasPeucker.simplifyE7(latE7, lonE7, epsilonMeters)
	}
}
