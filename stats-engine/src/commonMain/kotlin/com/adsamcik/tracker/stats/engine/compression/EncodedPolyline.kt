package com.adsamcik.tracker.stats.engine.compression

import com.adsamcik.tracker.stats.engine.algorithm.PolylineEncoder

/**
 * Google Encoded Polyline wrapper using [LatLng] coordinates.
 *
 * Delegates to [PolylineEncoder] but provides a cleaner API with [LatLng]
 * instead of raw Pair<Double, Double>.
 *
 * Encoding uses 1e-5 precision (~1.1m at the equator), which is sufficient
 * for route visualization and distance calculations.
 */
object EncodedPolyline {

	/**
	 * Encode a list of [LatLng] points to a Google Encoded Polyline string.
	 *
	 * @param points Geographic coordinates in decimal degrees
	 * @return Encoded polyline string (ASCII, URL-safe after escaping backslashes)
	 */
	fun encode(points: List<LatLng>): String {
		if (points.isEmpty()) return ""
		val pairs = points.map { Pair(it.lat, it.lng) }
		return PolylineEncoder.encode(pairs)
	}

	/**
	 * Decode a Google Encoded Polyline string back to [LatLng] points.
	 *
	 * @param encoded Encoded polyline string
	 * @return List of [LatLng] coordinates (precision limited to ~1e-5 degrees)
	 */
	fun decode(encoded: String): List<LatLng> {
		if (encoded.isEmpty()) return emptyList()
		return PolylineEncoder.decode(encoded).map { LatLng(it.first, it.second) }
	}
}
