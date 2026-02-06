package com.adsamcik.tracker.stats.engine.algorithm

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Douglas-Peucker line simplification algorithm.
 *
 * Reduces the number of points in a polyline while preserving its shape.
 * Used for route compression before raw location data is purged.
 *
 * Operates on E7 coordinates (latitude/longitude * 1e7 as integers)
 * to match the project's coordinate convention.
 */
object DouglasPeucker {

	/**
	 * A 2D point for simplification. Can represent lat/lng or any coordinate.
	 */
	data class Point(val x: Double, val y: Double)

	/**
	 * Simplify a polyline using the Douglas-Peucker algorithm.
	 *
	 * @param points Input polyline points
	 * @param epsilon Maximum perpendicular distance tolerance.
	 *   For geographic coordinates in degrees, ~0.00005 ≈ 5m.
	 * @return Simplified polyline (subset of input points)
	 */
	fun simplify(points: List<Point>, epsilon: Double): List<Point> {
		if (points.size <= 2) return points.toList()
		require(epsilon > 0.0) { "Epsilon must be positive" }

		val keep = BooleanArray(points.size)
		keep[0] = true
		keep[points.lastIndex] = true

		simplifySection(points, 0, points.lastIndex, epsilon, keep)

		return points.filterIndexed { index, _ -> keep[index] }
	}

	/**
	 * Simplify geographic coordinates given as E7 integers.
	 *
	 * @param latE7 Latitude values * 1e7
	 * @param lonE7 Longitude values * 1e7
	 * @param epsilonMeters Tolerance in meters
	 * @return Indices of points to keep
	 */
	fun simplifyE7(latE7: IntArray, lonE7: IntArray, epsilonMeters: Double): List<Int> {
		require(latE7.size == lonE7.size) { "Lat and lon arrays must be same length" }
		if (latE7.size <= 2) return latE7.indices.toList()

		// Convert epsilon from meters to approximate degrees
		// 1 degree ≈ 111,320 meters at equator
		val epsilonDeg = epsilonMeters / 111_320.0

		val points = latE7.indices.map { i ->
			Point(latE7[i] / 1e7, lonE7[i] / 1e7)
		}

		val keep = BooleanArray(points.size)
		keep[0] = true
		keep[points.lastIndex] = true

		simplifySection(points, 0, points.lastIndex, epsilonDeg, keep)

		return keep.indices.filter { keep[it] }
	}

	private fun simplifySection(
		points: List<Point>,
		startIndex: Int,
		endIndex: Int,
		epsilon: Double,
		keep: BooleanArray,
	) {
		if (endIndex - startIndex < 2) return

		var maxDist = 0.0
		var maxIndex = startIndex

		val start = points[startIndex]
		val end = points[endIndex]

		for (i in (startIndex + 1) until endIndex) {
			val dist = perpendicularDistance(points[i], start, end)
			if (dist > maxDist) {
				maxDist = dist
				maxIndex = i
			}
		}

		if (maxDist > epsilon) {
			keep[maxIndex] = true
			simplifySection(points, startIndex, maxIndex, epsilon, keep)
			simplifySection(points, maxIndex, endIndex, epsilon, keep)
		}
	}

	/**
	 * Calculate perpendicular distance from a point to a line segment.
	 */
	internal fun perpendicularDistance(
		point: Point,
		lineStart: Point,
		lineEnd: Point,
	): Double {
		val dx = lineEnd.x - lineStart.x
		val dy = lineEnd.y - lineStart.y

		if (dx == 0.0 && dy == 0.0) {
			// Line segment is a point
			val px = point.x - lineStart.x
			val py = point.y - lineStart.y
			return sqrt(px * px + py * py)
		}

		val numerator = abs(
			dy * point.x - dx * point.y +
					lineEnd.x * lineStart.y - lineEnd.y * lineStart.x
		)
		val denominator = sqrt(dx * dx + dy * dy)

		return numerator / denominator
	}
}
