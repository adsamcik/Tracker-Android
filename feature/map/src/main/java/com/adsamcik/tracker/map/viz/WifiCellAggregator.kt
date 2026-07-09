package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.graphics.GridAggregator
import kotlin.math.floor

/**
 * **Aggregator** for the Wi-Fi heatmaps. Unlike [GridHeatmapAggregator] it uses a Wi-Fi-specific cell
 * size — the coarser of the zoom-based grid and a quality-scaled floor [baseCellPerQuality] — and a
 * top-N cap rather than uniform thinning, matching the original Wi-Fi layers exactly.
 *
 * When [normalizeByMax] is true the per-cell weights are rescaled by the busiest cell's weight (the
 * Wi-Fi *count* view). This is viewport-relative and is preserved here verbatim from the old layer
 * for behavioural parity; the engine's frozen-normalization improvement is a separate, later change.
 */
class WifiCellAggregator(
	private val baseCellPerQuality: Double,
	private val normalizeByMax: Boolean,
) : Aggregator<WeightedGeoFeature, SpatialData.WeightedCells> {

	override fun aggregate(
		features: List<WeightedGeoFeature>,
		ctx: AggContext,
	): SpatialData.WeightedCells {
		val zoomCell = GridAggregator.cellSizeForZoom(ctx.zoom, ctx.quality)
		val qualityCell = baseCellPerQuality / ctx.quality.coerceAtLeast(MIN_QUALITY)
		val cellSize = maxOf(zoomCell, qualityCell).toFloat()

		val aggregated = aggregateWifiCells(features, ctx.maxPoints, cellSize)
		val result = if (normalizeByMax && aggregated.isNotEmpty()) {
			val maxWeight = aggregated.maxOf { it.weight }.coerceAtLeast(1.0)
			aggregated.map { it.copy(weight = (it.weight / maxWeight).coerceIn(0.0, 1.0)) }
		} else {
			aggregated
		}
		return SpatialData.WeightedCells(result)
	}

	private companion object {
		const val MIN_QUALITY = 0.6f
	}
}

/**
 * Buckets [input] into a fixed [cellSizeDegrees] grid, averaging each cell's weight and keeping the
 * [maxPoints] heaviest cells. Extracted verbatim from the old Wi-Fi layers.
 */
internal fun aggregateWifiCells(
	input: List<WeightedGeoFeature>,
	maxPoints: Int,
	cellSizeDegrees: Float,
): List<WeightedGeoFeature> {
	if (input.isEmpty()) return emptyList()

	class CellAccumulator {
		var latSum: Double = 0.0
		var lonSum: Double = 0.0
		var newestTime: Long = Long.MIN_VALUE
		var weightSum: Double = 0.0
		var count: Int = 0
	}

	val cellSize = cellSizeDegrees.toDouble().coerceAtLeast(0.0001)
	val cells = LinkedHashMap<Pair<Long, Long>, CellAccumulator>(input.size.coerceAtMost(maxPoints))

	input.forEach { feature ->
		val latBucket = floor(feature.lat / cellSize).toLong()
		val lonBucket = floor(feature.lon / cellSize).toLong()
		val cell = cells.getOrPut(latBucket to lonBucket) { CellAccumulator() }
		cell.latSum += feature.lat
		cell.lonSum += feature.lon
		cell.newestTime = maxOf(cell.newestTime, feature.time)
		cell.weightSum += feature.weight
		cell.count += 1
	}

	val aggregated = cells.values
		.map { cell ->
			WeightedGeoFeature(
				lat = cell.latSum / cell.count,
				lon = cell.lonSum / cell.count,
				time = cell.newestTime,
				weight = (cell.weightSum / cell.count).coerceIn(0.0, 1.0),
			)
		}
		.sortedByDescending { it.weight }

	return if (aggregated.size > maxPoints) aggregated.take(maxPoints) else aggregated
}
