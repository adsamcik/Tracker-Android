package com.adsamcik.tracker.osm.match

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure planar geometry helpers shared by the OSM map matcher. All inputs are
 * E7 coordinates (1e-7 degrees). Distances are computed with a local
 * equirectangular approximation, which is accurate to well under a metre over
 * the sub-kilometre spans the matcher cares about and far cheaper than a full
 * haversine in the per-candidate hot loop.
 *
 * Stateless and Android-free so it can be unit-tested on the JVM.
 */
internal object OsmGeometry {

	/** Metres per 1e-7 degree of latitude (~constant great-circle value). */
	const val METRES_PER_E7_DEG: Double = 0.01112

	private const val EPSILON: Double = 1e-9

	/** A road-snapped position with everything the matcher needs about it. */
	data class Projection(
		val segmentIndex: Int,
		val t: Double,
		val snappedLatE7: Int,
		val snappedLonE7: Int,
		val distanceM: Double,
		val arcLengthM: Double,
	)

	/** `cos(latitude)` for the equirectangular x-scale at the given E7 latitude. */
	fun cosLatAt(latE7: Int): Double = cos(latE7.toDouble() / 1e7 * Math.PI / 180.0)

	/**
	 * Returns the cumulative along-polyline distance in metres for each vertex.
	 * `out[0] == 0.0` and `out[i]` is the summed segment length up to vertex i.
	 * Returns an empty array for a polyline with fewer than 1 point.
	 */
	fun cumulativeArcLengthM(latsE7: IntArray, lonsE7: IntArray): DoubleArray {
		val n = min(latsE7.size, lonsE7.size)
		if (n == 0) return DoubleArray(0)
		val out = DoubleArray(n)
		for (i in 1 until n) {
			val cosLat = cosLatAt((latsE7[i] + latsE7[i - 1]) / 2)
			out[i] = out[i - 1] + segmentLengthM(
				latsE7[i - 1], lonsE7[i - 1],
				latsE7[i], lonsE7[i],
				cosLat,
			)
		}
		return out
	}

	/**
	 * Projects point `(pLatE7, pLonE7)` onto the polyline and returns the nearest
	 * [Projection], or `null` for a degenerate (<2 point) polyline. [cumArcLenM]
	 * must come from [cumulativeArcLengthM] for the same polyline.
	 */
	fun projectToPolyline(
		latsE7: IntArray,
		lonsE7: IntArray,
		cumArcLenM: DoubleArray,
		pLatE7: Int,
		pLonE7: Int,
	): Projection? {
		val n = min(latsE7.size, lonsE7.size)
		if (n < 2) return null
		val cosLat = cosLatAt(pLatE7)
		var bestDistSq = Double.MAX_VALUE
		var bestSeg = -1
		var bestT = 0.0
		for (i in 0 until n - 1) {
			val t = projectionParam(latsE7[i], lonsE7[i], latsE7[i + 1], lonsE7[i + 1], pLatE7, pLonE7, cosLat)
			val (cLat, cLon) = lerpE7(latsE7[i], lonsE7[i], latsE7[i + 1], lonsE7[i + 1], t)
			val dSq = planarDistSqM(cLat, cLon, pLatE7, pLonE7, cosLat)
			if (dSq < bestDistSq) {
				bestDistSq = dSq
				bestSeg = i
				bestT = t
			}
		}
		if (bestSeg < 0) return null
		val (snapLat, snapLon) = lerpE7(
			latsE7[bestSeg], lonsE7[bestSeg],
			latsE7[bestSeg + 1], lonsE7[bestSeg + 1],
			bestT,
		)
		val segLen = cumArcLenM[bestSeg + 1] - cumArcLenM[bestSeg]
		return Projection(
			segmentIndex = bestSeg,
			t = bestT,
			snappedLatE7 = snapLat,
			snappedLonE7 = snapLon,
			distanceM = sqrt(bestDistSq),
			arcLengthM = cumArcLenM[bestSeg] + bestT * segLen,
		)
	}

	/**
	 * Returns the road-following sub-path between two arc-length positions on the
	 * SAME polyline: the snapped start point, every OSM vertex strictly between
	 * the two positions (ordered start -> end), then the snapped end point.
	 * Handles travel in either direction.
	 */
	fun slicePolyline(
		latsE7: IntArray,
		lonsE7: IntArray,
		cumArcLenM: DoubleArray,
		startArcM: Double,
		endArcM: Double,
		startLatE7: Int,
		startLonE7: Int,
		endLatE7: Int,
		endLonE7: Int,
	): Pair<IntArray, IntArray> {
		val lo = min(startArcM, endArcM)
		val hi = max(startArcM, endArcM)
		val midLats = ArrayList<Int>()
		val midLons = ArrayList<Int>()
		for (i in cumArcLenM.indices) {
			val a = cumArcLenM[i]
			if (a > lo + EPSILON && a < hi - EPSILON) {
				midLats.add(latsE7[i])
				midLons.add(lonsE7[i])
			}
		}
		if (startArcM > endArcM) {
			midLats.reverse()
			midLons.reverse()
		}
		val outLat = IntArray(midLats.size + 2)
		val outLon = IntArray(midLats.size + 2)
		outLat[0] = startLatE7
		outLon[0] = startLonE7
		for (i in midLats.indices) {
			outLat[i + 1] = midLats[i]
			outLon[i + 1] = midLons[i]
		}
		outLat[outLat.size - 1] = endLatE7
		outLon[outLon.size - 1] = endLonE7
		return outLat to outLon
	}

	/** Great-circle-ish distance in metres between two E7 points. */
	fun distanceM(aLatE7: Int, aLonE7: Int, bLatE7: Int, bLonE7: Int): Double {
		val cosLat = cosLatAt((aLatE7 + bLatE7) / 2)
		return sqrt(planarDistSqM(aLatE7, aLonE7, bLatE7, bLonE7, cosLat))
	}

	private fun segmentLengthM(aLatE7: Int, aLonE7: Int, bLatE7: Int, bLonE7: Int, cosLat: Double): Double =
		sqrt(planarDistSqM(aLatE7, aLonE7, bLatE7, bLonE7, cosLat))

	private fun planarDistSqM(aLatE7: Int, aLonE7: Int, bLatE7: Int, bLonE7: Int, cosLat: Double): Double {
		val dx = signedLongitudeDeltaE7(bLonE7.toLong() - aLonE7.toLong()).toDouble() *
			METRES_PER_E7_DEG * cosLat
		val dy = (bLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
		return dx * dx + dy * dy
	}

	/** Clamped projection parameter t in [0,1] of P onto segment A->B. */
	private fun projectionParam(
		aLatE7: Int, aLonE7: Int,
		bLatE7: Int, bLonE7: Int,
		pLatE7: Int, pLonE7: Int,
		cosLat: Double,
	): Double {
		val bx = signedLongitudeDeltaE7(bLonE7.toLong() - aLonE7.toLong()).toDouble() *
			METRES_PER_E7_DEG * cosLat
		val by = (bLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
		val px = signedLongitudeDeltaE7(pLonE7.toLong() - aLonE7.toLong()).toDouble() *
			METRES_PER_E7_DEG * cosLat
		val py = (pLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
		val lenSq = bx * bx + by * by
		if (lenSq < EPSILON) return 0.0
		return max(0.0, min(1.0, (px * bx + py * by) / lenSq))
	}

	/** Linear interpolation between two E7 points, rounded back to E7 ints. */
	private fun lerpE7(aLatE7: Int, aLonE7: Int, bLatE7: Int, bLonE7: Int, t: Double): Pair<Int, Int> {
		val lat = aLatE7 + ((bLatE7 - aLatE7) * t)
		val lonDelta = signedLongitudeDeltaE7(bLonE7.toLong() - aLonE7.toLong())
		val lon = normalizeLongitudeE7(aLonE7.toLong() + Math.round(lonDelta.toDouble() * t))
		return Math.round(lat).toInt() to lon.toInt()
	}

	private fun normalizeLongitudeE7(value: Long): Long =
		Math.floorMod(value + HALF_WORLD_E7, WORLD_E7) - HALF_WORLD_E7

	private fun signedLongitudeDeltaE7(delta: Long): Long = normalizeLongitudeE7(delta)

	private const val WORLD_E7 = 3_600_000_000L
	private const val HALF_WORLD_E7 = WORLD_E7 / 2
}
