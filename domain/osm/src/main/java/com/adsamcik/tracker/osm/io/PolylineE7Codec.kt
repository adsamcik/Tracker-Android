package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7
import java.io.ByteArrayOutputStream

/**
 * Packs/unpacks the inline polyline blob persisted on
 * `com.adsamcik.tracker.shared.base.database.data.OsmWayEntity.geomPolylineE7`.
 *
 * Wire format:
 * ```
 *   count       : unsigned varint
 *   lat0_e7     : zigzag varint
 *   lon0_e7     : zigzag varint
 *   d_lat_e7[i] : zigzag varint (i = 1..count-1)
 *   d_lon_e7[i] : zigzag varint
 * ```
 *
 * Every decoded accumulated value must be an Earth-range coordinate before it
 * is narrowed to Int. The codec rejects truncated, overflowing, out-of-range,
 * and trailing-byte payloads. Decoded spatial identity is canonical: +180°
 * becomes -180°, and exact poles use longitude zero.
 */
object PolylineE7Codec {

	/** Encodes parallel checked Earth-coordinate arrays into a packed delta-varint blob. */
	fun encode(latsE7: IntArray, lonsE7: IntArray): ByteArray {
		require(latsE7.size == lonsE7.size) {
			"latsE7.size (${latsE7.size}) must equal lonsE7.size (${lonsE7.size})"
		}
		require(latsE7.size <= MAX_POINTS_PER_WAY) {
			"OSM polyline count ${latsE7.size} out of range [0, $MAX_POINTS_PER_WAY]"
		}
		val out = ByteArrayOutputStream(latsE7.size * 4)
		writeUnsignedVarInt(out, latsE7.size.toLong())
		if (latsE7.isEmpty()) return out.toByteArray()

		var previous = checkedCoordinate(latsE7[0].toLong(), lonsE7[0].toLong())
		writeZigZagVarInt(out, previous.latitudeE7.toLong())
		writeZigZagVarInt(out, previous.longitudeE7.toLong())
		for (index in 1 until latsE7.size) {
			val current = checkedCoordinate(latsE7[index].toLong(), lonsE7[index].toLong())
			writeZigZagVarInt(out, current.latitudeE7.toLong() - previous.latitudeE7.toLong())
			writeZigZagVarInt(out, current.longitudeE7.toLong() - previous.longitudeE7.toLong())
			previous = current
		}
		return out.toByteArray()
	}

	/**
	 * Decodes a strict packed polyline. Empty input is represented by one zero
	 * count byte. A malformed payload throws [IllegalArgumentException].
	 */
	fun decode(blob: ByteArray): Pair<IntArray, IntArray> {
		val reader = ByteReader(blob)
		val countLong = reader.readUnsignedVarInt()
		require(countLong in 0L..MAX_POINTS_PER_WAY.toLong()) {
			"OSM polyline count $countLong out of range [0, $MAX_POINTS_PER_WAY]"
		}
		val count = countLong.toInt()
		if (count == 0) {
			reader.requireEnd()
			return EMPTY to EMPTY
		}

		val lats = IntArray(count)
		val lons = IntArray(count)
		var rawLatitude = reader.readZigZagVarInt()
		var rawLongitude = reader.readZigZagVarInt()
		var coordinate = checkedCoordinate(rawLatitude, rawLongitude)
		lats[0] = coordinate.latitudeE7
		lons[0] = coordinate.longitudeE7
		for (index in 1 until count) {
			rawLatitude = checkedAdd(rawLatitude, reader.readZigZagVarInt())
			rawLongitude = checkedAdd(rawLongitude, reader.readZigZagVarInt())
			coordinate = checkedCoordinate(rawLatitude, rawLongitude)
			lats[index] = coordinate.latitudeE7
			lons[index] = coordinate.longitudeE7
		}
		reader.requireEnd()
		return lats to lons
	}

	private fun checkedCoordinate(latitudeE7: Long, longitudeE7: Long): CheckedCoordinateE7 =
		requireNotNull(CheckedCoordinateE7.fromE7OrNull(latitudeE7, longitudeE7)) {
			"OSM polyline coordinate outside Earth range"
		}

	private fun checkedAdd(base: Long, delta: Long): Long = try {
		Math.addExact(base, delta)
	} catch (_: ArithmeticException) {
		throw IllegalArgumentException("OSM polyline coordinate delta overflows Long")
	}

	private fun writeUnsignedVarInt(out: ByteArrayOutputStream, value: Long) {
		var remaining = value
		require(remaining >= 0L) { "Unsigned varint must be non-negative" }
		while ((remaining and 0x7FL.inv()) != 0L) {
			out.write(((remaining and 0x7FL) or 0x80L).toInt())
			remaining = remaining ushr 7
		}
		out.write(remaining.toInt())
	}

	private fun writeZigZagVarInt(out: ByteArrayOutputStream, value: Long) {
		val zigZag = (value shl 1) xor (value shr 63)
		writeUnsignedVarInt(out, zigZag)
	}

	private class ByteReader(private val buffer: ByteArray) {
		private var position = 0

		fun readUnsignedVarInt(): Long {
			var result = 0L
			var shift = 0
			while (true) {
				require(position < buffer.size) { "Truncated varint at offset $position" }
				val byte = buffer[position++].toInt() and 0xFF
				val payload = byte and 0x7F
				require(shift < 64 && (shift != 63 || payload <= 1)) {
					"Varint overflow at offset $position"
				}
				result = result or (payload.toLong() shl shift)
				if ((byte and 0x80) == 0) return result
				shift += 7
				require(shift < 64) { "Varint overflow at offset $position" }
			}
		}

		fun readZigZagVarInt(): Long {
			val raw = readUnsignedVarInt()
			return (raw ushr 1) xor -(raw and 1L)
		}

		fun requireEnd() {
			require(position == buffer.size) { "Trailing bytes after packed polyline at offset $position" }
		}
	}

	private const val MAX_POINTS_PER_WAY = 100_000
	private val EMPTY = IntArray(0)
}
