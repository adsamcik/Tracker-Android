package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.SegmentsAggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.gradientLine
import com.adsamcik.tracker.map.viz.mapViz

/** Fixed-elevation ramp: deep lowlands through cyan foothills to warm high mountains. */
internal val ALTITUDE_RIBBON_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0xFF313695.toInt(),
	0.25f to 0xFF2C7BB6.toInt(),
	0.5f to 0xFFABD9E9.toInt(),
	0.75f to 0xFFFDAE61.toInt(),
	1.0f to 0xFFD7191C.toInt(),
)

/** The recorded route painted by absolute altitude using a viewport-stable normalization. */
fun altitudeRibbon(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.Segments> =
	mapViz("altitude_ribbon")
		.source(altitudeSource(repo))
		.aggregate(SegmentsAggregator())
		.gradientLine(colorStops = ALTITUDE_RIBBON_RAMP, widthDp = 6f)
