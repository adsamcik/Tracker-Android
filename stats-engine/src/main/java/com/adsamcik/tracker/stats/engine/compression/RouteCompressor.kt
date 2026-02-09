package com.adsamcik.tracker.stats.engine.compression

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A raw location point from the tracking pipeline.
 */
data class LocationPoint(
	val lat: Double,
	val lng: Double,
	val timeMs: Long,
	val accuracyM: Float,
)

/**
 * Result of route compression.
 *
 * @property encodedPolyline Google Encoded Polyline of the simplified route
 * @property pointCount Number of raw input points consumed
 * @property simplifiedCount Number of points in the simplified route
 * @property startTimeMs Timestamp of the first point (epoch millis)
 * @property endTimeMs Timestamp of the last point (epoch millis)
 * @property distanceMeters Total path distance in meters (Haversine, simplified path)
 */
data class CompressedRoute(
	val encodedPolyline: String,
	val pointCount: Int,
	val simplifiedCount: Int,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val distanceMeters: Double,
)

/**
 * Combines Douglas-Peucker simplification and polyline encoding to produce
 * compact route representations from raw GPS data.
 *
 * Pipeline: raw points -> simplify (Douglas-Peucker) -> encode (polyline) -> [CompressedRoute]
 *
 * The default epsilon of 10 meters removes GPS jitter while preserving
 * turns and route shape. Typical compression ratios:
 * - Walking: 5-10x fewer points
 * - Driving: 10-20x fewer points
 */
object RouteCompressor {

	/** Default simplification tolerance in meters. */
	const val DEFAULT_EPSILON_METERS = 10.0

	/** Earth radius in meters (WGS-84 mean). */
	private const val EARTH_RADIUS_M = 6_371_000.0

	/**
	 * Compress a sequence of location points into a [CompressedRoute].
	 *
	 * @param points Raw location sequence (must be ordered by time)
	 * @param epsilonMeters Simplification tolerance in meters
	 * @return [CompressedRoute] or null if the sequence is empty
	 */
	fun compress(
		points: Sequence<LocationPoint>,
		epsilonMeters: Double = DEFAULT_EPSILON_METERS,
	): CompressedRoute? {
		val materialised = points.toList()
		if (materialised.isEmpty()) return null

		val latLngs = materialised.map { LatLng(it.lat, it.lng) }

		val simplified = if (latLngs.size <= 2) {
			latLngs
		} else {
			DouglasPeuckerSimplifier.simplify(latLngs, epsilonMeters)
		}

		val encodedPolyline = EncodedPolyline.encode(simplified)
		val distance = calculatePathDistance(simplified)

		return CompressedRoute(
			encodedPolyline = encodedPolyline,
			pointCount = materialised.size,
			simplifiedCount = simplified.size,
			startTimeMs = materialised.first().timeMs,
			endTimeMs = materialised.last().timeMs,
			distanceMeters = distance,
		)
	}

	/**
	 * Calculate total path distance using the Haversine formula.
	 *
	 * @param points Ordered list of geographic coordinates
	 * @return Total distance in meters
	 */
	internal fun calculatePathDistance(points: List<LatLng>): Double {
		if (points.size < 2) return 0.0

		var total = 0.0
		for (i in 0 until points.size - 1) {
			total += haversineDistance(points[i], points[i + 1])
		}
		return total
	}

	/**
	 * Haversine distance between two geographic points.
	 *
	 * @return Distance in meters
	 */
	internal fun haversineDistance(a: LatLng, b: LatLng): Double {
		val lat1 = Math.toRadians(a.lat)
		val lat2 = Math.toRadians(b.lat)
		val dLat = Math.toRadians(b.lat - a.lat)
		val dLng = Math.toRadians(b.lng - a.lng)

		val sinDLat = sin(dLat / 2)
		val sinDLng = sin(dLng / 2)

		val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLng * sinDLng
		val c = 2 * atan2(sqrt(h), sqrt(1 - h))

		return EARTH_RADIUS_M * c
	}
}
