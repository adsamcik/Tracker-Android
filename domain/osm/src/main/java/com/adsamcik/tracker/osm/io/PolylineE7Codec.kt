package com.adsamcik.tracker.osm.io

import java.io.ByteArrayOutputStream

/**
 * Packs/unpacks the inline polyline blob persisted on
 * `com.adsamcik.tracker.shared.base.database.data.OsmWayEntity.geomPolylineE7`.
 *
 * Wire format (matches the entity KDoc):
 * ```
 *   count       : unsigned varint
 *   lat0_e7     : zigzag varint
 *   lon0_e7     : zigzag varint
 *   d_lat_e7[i] : zigzag varint  (i = 1..count-1)
 *   d_lon_e7[i] : zigzag varint
 * ```
 *
 * Empty input (count == 0) encodes to a single 0x00 byte and decodes back to a
 * pair of empty arrays. Decoding throws when the buffer is truncated.
 */
object PolylineE7Codec {

	/**
	 * Encodes parallel latitude/longitude E7 arrays into a packed delta-varint
	 * blob. Both arrays MUST be the same length; an [IllegalArgumentException]
	 * is thrown otherwise.
	 */
	fun encode(latsE7: IntArray, lonsE7: IntArray): ByteArray {
		require(latsE7.size == lonsE7.size) {
			"latsE7.size (${latsE7.size}) must equal lonsE7.size (${lonsE7.size})"
		}
		val out = ByteArrayOutputStream(latsE7.size * 4)
		writeUnsignedVarInt(out, latsE7.size.toLong())
		if (latsE7.isEmpty()) return out.toByteArray()

		var prevLat = latsE7[0]
		var prevLon = lonsE7[0]
		writeZigZagVarInt(out, prevLat.toLong())
		writeZigZagVarInt(out, prevLon.toLong())
		for (i in 1 until latsE7.size) {
			val curLat = latsE7[i]
			val curLon = lonsE7[i]
			writeZigZagVarInt(out, (curLat.toLong() - prevLat.toLong()))
			writeZigZagVarInt(out, (curLon.toLong() - prevLon.toLong()))
			prevLat = curLat
			prevLon = curLon
		}
		return out.toByteArray()
	}

	/**
	 * Decodes a blob produced by [encode]. Returns parallel `(lats, lons)`
	 * arrays. Throws [IllegalArgumentException] when the blob is truncated or
	 * the declared count exceeds [MAX_POINTS_PER_WAY] (a sanity ceiling — OSM
	 * limits ways to 2000 nodes by convention).
	 */
	fun decode(blob: ByteArray): Pair<IntArray, IntArray> {
		val reader = ByteReader(blob)
		val count = reader.readUnsignedVarInt().toInt()
		require(count in 0..MAX_POINTS_PER_WAY) {
			"OSM polyline count $count out of range [0, $MAX_POINTS_PER_WAY]"
		}
		if (count == 0) return EMPTY to EMPTY

		val lats = IntArray(count)
		val lons = IntArray(count)
		lats[0] = reader.readZigZagVarInt().toInt()
		lons[0] = reader.readZigZagVarInt().toInt()
		for (i in 1 until count) {
			lats[i] = (lats[i - 1].toLong() + reader.readZigZagVarInt()).toInt()
			lons[i] = (lons[i - 1].toLong() + reader.readZigZagVarInt()).toInt()
		}
		return lats to lons
	}

	private const val MAX_POINTS_PER_WAY = 100_000
	private val EMPTY = IntArray(0)

	private fun writeUnsignedVarInt(out: ByteArrayOutputStream, value: Long) {
		var v = value
		require(v >= 0) { "unsigned varint must be >= 0, was $v" }
		while ((v and 0x7FL.inv()) != 0L) {
			out.write(((v and 0x7FL) or 0x80L).toInt())
			v = v ushr 7
		}
		out.write(v.toInt())
	}

	private fun writeZigZagVarInt(out: ByteArrayOutputStream, value: Long) {
		val zz = (value shl 1) xor (value shr 63)
		writeUnsignedVarInt(out, zz)
	}

	private class ByteReader(private val buf: ByteArray) {
		private var pos = 0

		fun readUnsignedVarInt(): Long {
			var result = 0L
			var shift = 0
			while (true) {
				require(pos < buf.size) { "Truncated varint at offset $pos" }
				val b = buf[pos++].toInt() and 0xFF
				result = result or ((b and 0x7F).toLong() shl shift)
				if ((b and 0x80) == 0) return result
				shift += 7
				require(shift <= 63) { "varint overflow at offset $pos" }
			}
			@Suppress("UNREACHABLE_CODE")
			return result
		}

		fun readZigZagVarInt(): Long {
			val raw = readUnsignedVarInt()
			return (raw ushr 1) xor -(raw and 1L)
		}
	}
}
