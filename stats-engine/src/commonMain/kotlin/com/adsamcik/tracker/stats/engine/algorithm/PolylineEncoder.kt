package com.adsamcik.tracker.stats.engine.algorithm

/**
 * Google Encoded Polyline encoder/decoder.
 *
 * Encodes a series of lat/lng coordinates into a compact string
 * using the Google Encoded Polyline Algorithm Format.
 * See: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 *
 * Average ~4 bytes per coordinate pair (vs 16 bytes raw).
 * A typical trip of 100-200 points encodes to ~400-800 bytes.
 */
object PolylineEncoder {

	/**
	 * Encode a list of lat/lng pairs (in degrees) to a polyline string.
	 *
	 * @param coordinates List of (latitude, longitude) pairs in degrees
	 * @return Encoded polyline string
	 */
	fun encode(coordinates: List<Pair<Double, Double>>): String {
		if (coordinates.isEmpty()) return ""

		val result = StringBuilder()
		var prevLat = 0
		var prevLng = 0

		for ((lat, lng) in coordinates) {
			val latE5 = (lat * 1e5).toInt()
			val lngE5 = (lng * 1e5).toInt()

			encodeValue(latE5 - prevLat, result)
			encodeValue(lngE5 - prevLng, result)

			prevLat = latE5
			prevLng = lngE5
		}

		return result.toString()
	}

	/**
	 * Encode from E7 integer arrays (the project's internal format).
	 *
	 * @param latE7 Latitude values * 1e7
	 * @param lonE7 Longitude values * 1e7
	 * @return Encoded polyline string
	 */
	fun encodeE7(latE7: IntArray, lonE7: IntArray): String {
		require(latE7.size == lonE7.size) { "Lat and lon arrays must be same length" }
		if (latE7.isEmpty()) return ""

		val result = StringBuilder()
		var prevLat = 0
		var prevLng = 0

		for (i in latE7.indices) {
			// Convert E7 to E5 (divide by 100)
			val latE5 = latE7[i] / 100
			val lngE5 = lonE7[i] / 100

			encodeValue(latE5 - prevLat, result)
			encodeValue(lngE5 - prevLng, result)

			prevLat = latE5
			prevLng = lngE5
		}

		return result.toString()
	}

	/**
	 * Decode a polyline string to lat/lng pairs (in degrees).
	 *
	 * @param encoded Encoded polyline string
	 * @return List of (latitude, longitude) pairs in degrees
	 */
	fun decode(encoded: String): List<Pair<Double, Double>> {
		if (encoded.isEmpty()) return emptyList()

		val result = mutableListOf<Pair<Double, Double>>()
		var index = 0
		var lat = 0
		var lng = 0

		while (index < encoded.length) {
			val (latDelta, nextIndex1) = decodeValue(encoded, index)
			index = nextIndex1
			lat += latDelta

			val (lngDelta, nextIndex2) = decodeValue(encoded, index)
			index = nextIndex2
			lng += lngDelta

			result.add(Pair(lat / 1e5, lng / 1e5))
		}

		return result
	}

	private fun encodeValue(value: Int, result: StringBuilder) {
		var v = if (value < 0) (value shl 1).inv() else value shl 1

		while (v >= 0x20) {
			result.append((((v and 0x1F) or 0x20) + 63).toChar())
			v = v shr 5
		}
		result.append((v + 63).toChar())
	}

	private fun decodeValue(encoded: String, startIndex: Int): Pair<Int, Int> {
		var index = startIndex
		var result = 0
		var shift = 0
		var b: Int

		do {
			b = encoded[index].code - 63
			index++
			result = result or ((b and 0x1F) shl shift)
			shift += 5
		} while (b >= 0x20)

		val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
		return Pair(value, index)
	}
}
