package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.graphics.GridAggregator
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * Grid-heatmap stage implementations: the [Aggregator] and [Encoder] shared by every point-density
 * heatmap (location, speed, cell, wifi, ...), plus the [heatmap] DSL terminal. The only thing that
 * varies between these heatmaps is the source query, the [CellWeighting] (value-vs-mass), and the
 * colour ramp / radius — everything else is this shared code.
 */

/** Maps an aggregated grid cell to its `[0, 1]` heatmap weight — the value-vs-mass choice. */
fun interface CellWeighting {
	fun weightOf(cell: GridAggregator.AggregatedCell): Double

	companion object {
		/** Absolute log-density of the cell's visit COUNT — colour means "how often was I here". */
		val Density = CellWeighting { GridAggregator.densityWeight(it.count) }

		/** The cell's averaged per-fix VALUE — colour means a measured quantity (e.g. mean speed). */
		val Average = CellWeighting { it.weight }
	}
}

/**
 * **Aggregator** for grid heatmaps: raw weighted fixes -> [SpatialData.WeightedCells]. Grid bucketing
 * at every zoom stops a travelled path's tightly-spaced points from piling up inside one heatmap
 * radius and saturating to red; [weighting] chooses count-density vs averaged value. A uniform-thinning
 * cap keeps the cell count within the per-refresh render budget.
 */
class GridHeatmapAggregator(
	private val weighting: CellWeighting,
) : Aggregator<WeightedGeoFeature, SpatialData.WeightedCells> {

	override fun aggregate(
		features: List<WeightedGeoFeature>,
		ctx: AggContext,
	): SpatialData.WeightedCells {
		val cellSize = GridAggregator.cellSizeForZoom(ctx.zoom, ctx.quality)
		val cells = GridAggregator.aggregate(features, cellSize)
		val weighted = cells.map { cell ->
			WeightedGeoFeature(
				lat = cell.lat,
				lon = cell.lon,
				time = cell.newestTime,
				weight = weighting.weightOf(cell),
			)
		}
		val capped = if (weighted.size > ctx.maxPoints) {
			val step = (weighted.size / ctx.maxPoints).coerceAtLeast(1)
			weighted.filterIndexed { index, _ -> index % step == 0 }
		} else {
			weighted
		}
		return SpatialData.WeightedCells(capped)
	}
}

/**
 * **Encoder** for grid heatmaps: [SpatialData.WeightedCells] + style -> a MapLibre native heatmap
 * config. Radius is quality-scaled from [baseRadiusPx] (via [GridAggregator.radiusForQuality]) so the
 * blob stays coherent with the quality-scaled grid resolution.
 */
class HeatmapEncoder(
	private val colorStops: List<Pair<Float, Int>>,
	private val baseRadiusPx: Float,
	private val intensity: Float = 1f,
	private val opacity: Float = 0.8f,
) : Encoder<SpatialData.WeightedCells> {

	override fun encode(field: SpatialData.WeightedCells, ctx: RenderContext): MapLibreLayerConfig =
		MapLibreLayerConfig.Heatmap(
			geoJson = GeoJsonConverter.pointsToFeatureCollection(field.cells),
			colorStops = colorStops,
			radiusPx = GridAggregator.radiusForQuality(baseRadiusPx, ctx.quality),
			intensity = intensity,
			opacity = opacity,
			weightProperty = "weight",
		)
}

/**
 * DSL terminal for the grid-heatmap family. Available only when the pipeline's field type is
 * [SpatialData.WeightedCells], so a heatmap encoder cannot be paired with any other aggregator output
 * — the mismatch is a compile error.
 */
fun <Feature> EncodeStep<Feature, SpatialData.WeightedCells>.heatmap(
	colorStops: List<Pair<Float, Int>>,
	baseRadiusPx: Float,
	intensity: Float = 1f,
	opacity: Float = 0.8f,
): VizPipeline<Feature, SpatialData.WeightedCells> =
	encode(HeatmapEncoder(colorStops, baseRadiusPx, intensity, opacity))
