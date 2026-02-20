package com.adsamcik.tracker.stats.engine.exploration

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Simplified S2 cell ID computation for exploration tracking.
 * Converts lat/lng coordinates to S2 cell tokens at configurable levels.
 *
 * S2 cells provide near-uniform area coverage of the Earth's surface,
 * making them ideal for exploration tracking where consistent cell sizes matter.
 *
 * Level reference:
 * - Level 12: ~3.3 km² (neighborhood)
 * - Level 14: ~0.8 km² (city block) -- default for exploration
 * - Level 16: ~0.05 km² (building)
 *
 * Uses quadratic projection and Hilbert-curve lookup tables identical to the
 * reference C++ / Java implementation, producing bit-identical cell IDs.
 *
 * Thread-safe: all state is immutable after initialisation; lookup tables are
 * populated in the object's init block.
 */
object S2CellId {
	const val DEFAULT_LEVEL: Int = 14
	const val MAX_LEVEL: Int = 30

	internal const val POS_BITS: Int = 2 * MAX_LEVEL + 1 // 61
	internal const val LOOKUP_BITS: Int = 4
	private const val SWAP_MASK: Int = 0x01
	private const val INVERT_MASK: Int = 0x02

	private const val DEG_TO_RAD: Double = kotlin.math.PI / 180.0
	private const val RAD_TO_DEG: Double = 180.0 / kotlin.math.PI

	// --- Lookup tables (initialised in init block) ---
	internal val LOOKUP_POS = IntArray(1024)
	internal val LOOKUP_IJ = IntArray(1024)

	// POS_TO_IJ[orientation][pos] → sub-cell (i<<1|j) for each Hilbert orientation.
	// Rows: canonical, SWAP, INVERT, SWAP|INVERT.
	private val POS_TO_IJ = arrayOf(
		intArrayOf(0, 1, 3, 2),
		intArrayOf(0, 3, 1, 2),
		intArrayOf(2, 3, 1, 0),
		intArrayOf(2, 1, 3, 0),
	)
	private val POS_TO_ORIENTATION = intArrayOf(SWAP_MASK, 0, 0, INVERT_MASK or SWAP_MASK)

	init {
		initLookupTables()
	}

	// ---- Lookup-table initialisation (Hilbert curve) ----

	private fun initLookupTables() {
		initLookup(0, 0, 0, 0, 0, 0)
		initLookup(0, 0, 0, SWAP_MASK, 0, SWAP_MASK)
		initLookup(0, 0, 0, INVERT_MASK, 0, INVERT_MASK)
		initLookup(0, 0, 0, SWAP_MASK or INVERT_MASK, 0, SWAP_MASK or INVERT_MASK)
	}

	private fun initLookup(
		level: Int,
		i: Int,
		j: Int,
		origOrientation: Int,
		pos: Int,
		orientation: Int,
	) {
		if (level == LOOKUP_BITS) {
			val ij = (i shl LOOKUP_BITS) or j
			LOOKUP_POS[(ij shl 2) or origOrientation] = (pos shl 2) or orientation
			LOOKUP_IJ[(pos shl 2) or origOrientation] = (ij shl 2) or orientation
		} else {
			for (p in 0..3) {
				val r = POS_TO_IJ[orientation][p]
				initLookup(
					level + 1,
					(i shl 1) + (r ushr 1),
					(j shl 1) + (r and 1),
					origOrientation,
					(pos shl 2) or p,
					orientation xor POS_TO_ORIENTATION[p],
				)
			}
		}
	}

	// ---- Public API ----

	/**
	 * Convert latitude/longitude (degrees) to an S2 cell ID at the given level.
	 * Returns a 64-bit cell ID.
	 *
	 * @param latDeg Latitude in degrees (-90..90).
	 * @param lngDeg Longitude in degrees (-180..180).
	 * @param level S2 cell level (0..[MAX_LEVEL]).
	 */
	fun fromLatLng(latDeg: Double, lngDeg: Double, level: Int = DEFAULT_LEVEL): Long {
		require(level in 0..MAX_LEVEL) { "level must be in [0, $MAX_LEVEL]" }
		val lat = latDeg * DEG_TO_RAD
		val lng = lngDeg * DEG_TO_RAD
		val (face, u, v) = xyzToFaceUV(
			cos(lat) * cos(lng),
			cos(lat) * sin(lng),
			sin(lat),
		)
		val s = uvToST(u)
		val t = uvToST(v)
		val i = stToIJ(s)
		val j = stToIJ(t)
		return parent(fromFaceIJ(face, i, j), level)
	}

	/**
	 * Convert a cell ID to a compact hex token string (for storage).
	 * Trailing zeros are stripped. The special zero cell produces "X".
	 */
	fun toToken(cellId: Long): String {
		if (cellId == 0L) return "X"
		val hex = cellId.toULong().toString(16).padStart(16, '0')
		return hex.trimEnd('0')
	}

	/**
	 * Reconstruct a cell ID from its token string.
	 */
	fun fromToken(token: String): Long {
		if (token == "X") return 0L
		val padded = token.padEnd(16, '0')
		return padded.toULong(16).toLong()
	}

	/**
	 * Get the parent cell ID at a coarser level.
	 *
	 * @param cellId The cell ID to find the parent of.
	 * @param parentLevel Target level, must be <= the cell's current level.
	 * @throws IllegalArgumentException if parentLevel > current level or out of range.
	 */
	fun parent(cellId: Long, parentLevel: Int): Long {
		val currentLevel = level(cellId)
		require(parentLevel in 0..currentLevel) {
			"parentLevel $parentLevel must be in [0, $currentLevel]"
		}
		val newLsb = lsbForLevel(parentLevel)
		return (cellId and (-newLsb)) or newLsb
	}

	/**
	 * Get the level of a cell ID.
	 * Returns -1 for the special zero cell.
	 *
	 * The sentinel bit for level k sits at bit position 2*(30-k).
	 * So level = 30 - (numberOfTrailingZeros / 2).
	 */
	fun level(cellId: Long): Int {
		if (cellId == 0L) return -1
		val trailingZeros = cellId.countTrailingZeroBits()
		return MAX_LEVEL - (trailingZeros / 2)
	}

	/**
	 * Get the face (0..5) of the S2 cube that this cell belongs to.
	 */
	fun face(cellId: Long): Int = (cellId ushr POS_BITS).toInt()

	/**
	 * Get the approximate center lat/lng of a cell (degrees).
	 * Returns Pair(latitude, longitude).
	 *
	 * For non-leaf cells, extracted i/j have lower bits zeroed (corner).
	 * We add half the cell width to shift to the cell center.
	 */
	fun toLatLng(cellId: Long): Pair<Double, Double> {
		val (f, rawI, rawJ) = cellIdToIJ(cellId)
		val cellLevel = level(cellId)
		val i: Int
		val j: Int
		if (cellLevel < MAX_LEVEL) {
			// Zero sub-level bits (which encode a specific corner from
			// the sentinel, not the center) then add half the cell width.
			val shift = MAX_LEVEL - cellLevel
			val mask = (-1) shl shift
			val half = 1 shl (shift - 1)
			i = (rawI and mask) or half
			j = (rawJ and mask) or half
		} else {
			i = rawI
			j = rawJ
		}
		val u = stToUV(ijToST(i))
		val v = stToUV(ijToST(j))
		val (x, y, z) = faceUVToXYZ(f, u, v)
		val lat = atan2(z, sqrt(x * x + y * y))
		val lng = atan2(y, x)
		return (lat * RAD_TO_DEG) to (lng * RAD_TO_DEG)
	}

	// ---- Projection helpers ----

	/** Quadratic ST -> UV. */
	internal fun stToUV(s: Double): Double {
		return if (s >= 0.5) {
			(1.0 / 3.0) * (4.0 * s * s - 1.0)
		} else {
			(1.0 / 3.0) * (1.0 - 4.0 * (1.0 - s) * (1.0 - s))
		}
	}

	/** Quadratic UV -> ST. */
	internal fun uvToST(u: Double): Double {
		return if (u >= 0) {
			0.5 * sqrt(1.0 + 3.0 * u)
		} else {
			1.0 - 0.5 * sqrt(1.0 - 3.0 * u)
		}
	}

	/** ST coordinate [0,1] -> IJ integer [0, 2^30 - 1]. */
	internal fun stToIJ(s: Double): Int {
		val maxSize = 1 shl MAX_LEVEL // 2^30
		return max(0, min(maxSize - 1, floor(maxSize.toDouble() * s).toInt()))
	}

	/** IJ integer -> ST coordinate. */
	internal fun ijToST(ij: Int): Double {
		val maxSize = 1 shl MAX_LEVEL
		return (ij.toDouble() + 0.5) / maxSize.toDouble()
	}

	/** Map (x,y,z) to (face, u, v). Reference S2 convention. */
	internal fun xyzToFaceUV(x: Double, y: Double, z: Double): Triple<Int, Double, Double> {
		val ax = abs(x)
		val ay = abs(y)
		val az = abs(z)
		val face: Int
		val u: Double
		val v: Double
		if (ax >= ay && ax >= az) {
			face = if (x > 0) 0 else 3
			if (x > 0) { u = y / x; v = z / x } else { u = z / x; v = y / x }
		} else if (ay >= ax && ay >= az) {
			face = if (y > 0) 1 else 4
			if (y > 0) { u = -x / y; v = z / y } else { u = z / y; v = -x / y }
		} else {
			face = if (z > 0) 2 else 5
			if (z > 0) { u = -x / z; v = -y / z } else { u = -y / z; v = -x / z }
		}
		return Triple(face, u, v)
	}

	/** Map (face, u, v) -> (x, y, z). Reference S2 convention. */
	internal fun faceUVToXYZ(face: Int, u: Double, v: Double): Triple<Double, Double, Double> {
		return when (face) {
			0 -> Triple(1.0, u, v)
			1 -> Triple(-u, 1.0, v)
			2 -> Triple(-u, -v, 1.0)
			3 -> Triple(-1.0, -v, -u)
			4 -> Triple(v, -1.0, -u)
			5 -> Triple(v, u, -1.0)
			else -> error("Invalid face: $face")
		}
	}

	// ---- Cell ID construction from face/i/j via Hilbert curve ----

	internal fun fromFaceIJ(face: Int, i: Int, j: Int): Long {
		var n = face.toLong() shl (POS_BITS - 1)
		var bits = face and SWAP_MASK
		for (k in 7 downTo 0) {
			val mask = (1 shl LOOKUP_BITS) - 1
			bits += ((i ushr (k * LOOKUP_BITS)) and mask) shl (LOOKUP_BITS + 2)
			bits += ((j ushr (k * LOOKUP_BITS)) and mask) shl 2
			bits = LOOKUP_POS[bits]
			n = n or ((bits ushr 2).toLong() shl (k * 2 * LOOKUP_BITS))
			bits = bits and (SWAP_MASK or INVERT_MASK)
		}
		return n * 2 + 1
	}

	/** Reverse: cell ID -> (face, i, j). Direct port of reference S2 toFaceIJOrientation. */
	internal fun cellIdToIJ(cellId: Long): Triple<Int, Int, Int> {
		val face = (cellId ushr POS_BITS).toInt()
		var bits = face and SWAP_MASK
		var i = 0
		var j = 0
		for (k in 7 downTo 0) {
			val nbits = if (k == 7) MAX_LEVEL - 7 * LOOKUP_BITS else LOOKUP_BITS
			bits += ((cellId ushr (k * 2 * LOOKUP_BITS + 1)).toInt()
				and ((1 shl (2 * nbits)) - 1)) shl 2
			bits = LOOKUP_IJ[bits]
			i += (bits ushr (LOOKUP_BITS + 2)) shl (k * LOOKUP_BITS)
			j += ((bits ushr 2) and ((1 shl LOOKUP_BITS) - 1)) shl (k * LOOKUP_BITS)
			bits = bits and (SWAP_MASK or INVERT_MASK)
		}
		val ijMask = (1 shl MAX_LEVEL) - 1
		return Triple(face, i and ijMask, j and ijMask)
	}

	/** Lowest set bit for a given level. */
	private fun lsbForLevel(level: Int): Long = 1L shl (2 * (MAX_LEVEL - level))
}
