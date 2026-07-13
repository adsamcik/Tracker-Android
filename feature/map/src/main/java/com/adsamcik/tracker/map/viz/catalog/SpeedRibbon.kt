package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.SegmentsAggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.gradientLine
import com.adsamcik.tracker.map.viz.mapViz

/**
 * **Speed Ribbon** — the first "ribbon" visualization on the map-visualization engine, and the first
 * use of the [SpatialData.Segments] shape. It paints your recorded route as a single line whose
 * colour flows with your speed along the way: a slow amble glows cool violet, a sprint or a drive
 * burns red. Where the speed *heatmap* smears speed into a density cloud, the ribbon keeps the route
 * intact and shows exactly *where* you sped up and slowed down.
 *
 * It reuses the existing [speedSource] (fixes weighted by normalised speed) — the only new pieces are
 * the [SegmentsAggregator] (order into a path) and the gradient-line encoder, both engine-generic.
 */

/**
 * Opaque speed ramp for the ribbon: same hue progression as the speed heatmap (slow → violet, fast →
 * red) but fully opaque at every stop, since a line — unlike a heatmap — must stay visible even where
 * you moved slowly. The six stops line up with the existing speed legend strings.
 */
val SPEED_RIBBON_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0xFF9966FF.toInt(),
	0.2f to 0xFF66CCFF.toInt(),
	0.4f to 0xFF66FF66.toInt(),
	0.6f to 0xFFFFFF66.toInt(),
	0.8f to 0xFFFF8000.toInt(),
	1.0f to 0xFFFF3333.toInt(),
)

/** Speed ribbon: the recorded route coloured continuously by movement speed. */
fun speedRibbon(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.Segments> =
	mapViz("speed_ribbon")
		.source(speedSource(repo, SourceRowSelection.NewestOrdered))
		.aggregate(SegmentsAggregator())
		.gradientLine(colorStops = SPEED_RIBBON_RAMP, widthDp = 6f)
