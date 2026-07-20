package com.adsamcik.tracker.shared.base.database.analysis

import com.adsamcik.tracker.shared.base.database.data.AnalysisCell
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor

/**
 * Versioned sparse metric lattice whose east/west scale is local to each latitude row.
 *
 * A plain global equirectangular grid makes a nominal 25 m cell progressively narrower toward the
 * poles. Scaling longitude by the row-centre cosine keeps both axes approximately metric while
 * retaining deterministic global cell IDs and explicit WGS84 display bounds.
 */
object MetricAnalysisGrid {
	const val GRID_VERSION: Int = 1
	val RESOLUTIONS_M: List<Int> = listOf(25, 50, 100, 250, 500, 1_000)

	private const val EARTH_RADIUS_M = 6_371_008.8
	private const val HALF_WORLD_Y_M = PI * EARTH_RADIUS_M / 2.0
	private const val R90_SIGMA_FACTOR = 2.145966
	private const val MIN_ROW_COSINE = 0.01

	data class WeightedCell(val cell: AnalysisCell, val probability: Double)
	data class AllocatedCellMillis(val cell: AnalysisCell, val millis: Long)

	fun finestSupportedResolution(effectiveR90M: Double?): Int? {
		if (effectiveR90M == null || !effectiveR90M.isFinite() || effectiveR90M < 0.0) return null
		return RESOLUTIONS_M.firstOrNull { effectiveR90M <= it * 0.5 }
	}

	fun cell(latDeg: Double, lonDeg: Double, resolutionM: Int): AnalysisCell {
		require(resolutionM in RESOLUTIONS_M)
		// The positive pole is the same closed endpoint problem as +180 longitude: assigning it
		// beyond the final row would produce a zero-height display cell after WGS84 clamping.
		val normalizedLat = latDeg.coerceIn(-90.0, Math.nextDown(90.0))
		val y = Math.toRadians(normalizedLat) * EARTH_RADIUS_M
		val yIndex = floor((y + HALF_WORLD_Y_M) / resolutionM).toLong()
		return cellInRow(lonDeg, yIndex, resolutionM)
	}

	/** Integrates an isotropic Gaussian posterior over nearby cells using centre quadrature. */
	fun weightedCells(
		latDeg: Double,
		lonDeg: Double,
		effectiveR90M: Double,
		resolutionM: Int,
	): List<WeightedCell> {
		require(finestSupportedResolution(effectiveR90M)?.let { resolutionM >= it } == true) {
			"$resolutionM m does not support r90=$effectiveR90M m"
		}
		val center = cell(latDeg, lonDeg, resolutionM)
		if (effectiveR90M == 0.0) return listOf(WeightedCell(center, 1.0))

		val sigmaM = effectiveR90M / R90_SIGMA_FACTOR
		val cosLat = cos(Math.toRadians(latDeg)).coerceAtLeast(MIN_ROW_COSINE)
		val weighted = ArrayList<Pair<AnalysisCell, Double>>(9)
		for (dy in -1L..1L) {
			val rowAnchor = cellInRow(lonDeg, center.yIndex + dy, resolutionM)
			for (dx in -1L..1L) {
				val candidate = cell(rowAnchor.xIndex + dx, rowAnchor.yIndex, resolutionM)
				// Add after widening: valid E7 endpoints can overflow Int when summed near
				// the poles or antimeridian even though their midpoint is representable.
				val candidateLat =
					(candidate.minLatE7.toLong() + candidate.maxLatE7.toLong()) / 2.0 / 1e7
				val candidateLon =
					(candidate.minLonE7.toLong() + candidate.maxLonE7.toLong()) / 2.0 / 1e7
				val northM = Math.toRadians(candidateLat - latDeg) * EARTH_RADIUS_M
				val longitudeDelta = ((candidateLon - lonDeg + 540.0) % 360.0) - 180.0
				val eastM = Math.toRadians(longitudeDelta) * EARTH_RADIUS_M * cosLat
				val distanceSquared = northM * northM + eastM * eastM
				weighted += candidate to exp(-distanceSquared / (2.0 * sigmaM * sigmaM))
			}
		}
		val total = weighted.sumOf { it.second }
		return weighted.map { (candidate, weight) -> WeightedCell(candidate, weight / total) }
	}

	/**
	 * Converts a normalized posterior to exact integer milliseconds using deterministic largest
	 * remainder allocation. Canonical storage never relies on floating-point sums for time mass.
	 */
	fun allocateMillis(totalMs: Long, weightedCells: List<WeightedCell>): List<AllocatedCellMillis> {
		require(totalMs >= 0L)
		if (weightedCells.isEmpty()) {
			require(totalMs == 0L) { "Non-zero presence mass requires at least one cell" }
			return emptyList()
		}
		val probabilitySum = weightedCells.sumOf(WeightedCell::probability)
		require(probabilitySum.isFinite() && kotlin.math.abs(probabilitySum - 1.0) <= 1e-9) {
			"Cell probabilities must sum to one, got $probabilitySum"
		}

		data class Share(
			val index: Int,
			val cell: AnalysisCell,
			val floorMs: Long,
			val remainder: Double,
		)

		val shares = weightedCells.mapIndexed { index, weighted ->
			require(weighted.probability.isFinite() && weighted.probability >= 0.0)
			val exact = totalMs.toDouble() * weighted.probability
			val floorMs = floor(exact).toLong()
			Share(index, weighted.cell, floorMs, exact - floorMs)
		}
		val allocated = LongArray(shares.size) { shares[it].floorMs }
		val remainderMs = totalMs - allocated.sum()
		check(remainderMs in 0L..shares.size.toLong())
		shares.sortedWith(
			compareByDescending<Share> { it.remainder }
				.thenBy { it.cell.cellId }
		).take(remainderMs.toInt()).forEach { allocated[it.index]++ }

		check(allocated.sum() == totalMs)
		return shares.map { share -> AllocatedCellMillis(share.cell, allocated[share.index]) }
	}

	private fun cell(xIndex: Long, yIndex: Long, resolutionM: Int): AnalysisCell {
		val minY = yIndex * resolutionM - HALF_WORLD_Y_M
		val maxY = minY + resolutionM
		val rowCenterLatRadians = ((minY + maxY) / 2.0 / EARTH_RADIUS_M)
			.coerceIn(-PI / 2.0, PI / 2.0)
		val rowCosine = cos(rowCenterLatRadians).coerceAtLeast(MIN_ROW_COSINE)
		val halfRowWidthM = PI * EARTH_RADIUS_M * rowCosine
		val cellCount = ceil(2.0 * halfRowWidthM / resolutionM).toLong().coerceAtLeast(1L)
		val wrappedXIndex = Math.floorMod(xIndex, cellCount)
		val minX = wrappedXIndex * resolutionM - halfRowWidthM
		val maxX = minX + resolutionM
		return AnalysisCell(
			cellId = "g$GRID_VERSION:r$resolutionM:$wrappedXIndex:$yIndex",
			gridVersion = GRID_VERSION,
			resolutionM = resolutionM,
			xIndex = wrappedXIndex,
			yIndex = yIndex,
			minLatE7 = degreesToE7(Math.toDegrees(minY / EARTH_RADIUS_M).coerceIn(-90.0, 90.0)),
			minLonE7 = degreesToE7(
				Math.toDegrees(minX / (EARTH_RADIUS_M * rowCosine)).coerceIn(-180.0, 180.0),
			),
			maxLatE7 = degreesToE7(Math.toDegrees(maxY / EARTH_RADIUS_M).coerceIn(-90.0, 90.0)),
			maxLonE7 = degreesToE7(
				Math.toDegrees(maxX / (EARTH_RADIUS_M * rowCosine)).coerceIn(-180.0, 180.0),
			),
		)
	}

	private fun cellInRow(lonDeg: Double, yIndex: Long, resolutionM: Int): AnalysisCell {
		val minY = yIndex * resolutionM - HALF_WORLD_Y_M
		val rowCenterLatRadians = (minY + resolutionM / 2.0) / EARTH_RADIUS_M
		val rowCosine = cos(rowCenterLatRadians).coerceAtLeast(MIN_ROW_COSINE)
		val halfRowWidthM = PI * EARTH_RADIUS_M * rowCosine
		// +180 and -180 are the same meridian. Keep the positive endpoint inside the final
		// non-empty cell rather than manufacturing a zero-width cell beyond the antimeridian.
		val normalizedLon = lonDeg.coerceIn(-180.0, Math.nextDown(180.0))
		val x = Math.toRadians(normalizedLon) * EARTH_RADIUS_M * rowCosine
		val xIndex = floor((x + halfRowWidthM) / resolutionM).toLong()
		return cell(xIndex, yIndex, resolutionM)
	}

	private fun degreesToE7(value: Double): Int = (value * 1e7).toInt()
}
