package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.SegmentsAggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.gradientLine
import com.adsamcik.tracker.map.viz.mapViz

/** Discrete motion-state colours: unknown grey, still blue, moving green. */
internal val ACTIVITY_RIBBON_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0xFF78909C.toInt(),
	0.49f to 0xFF78909C.toInt(),
	0.5f to 0xFF42A5F5.toInt(),
	0.51f to 0xFF42A5F5.toInt(),
	1.0f to 0xFF43A047.toInt(),
)

/**
 * The recorded route painted by the trustworthy per-fix motion state. The user-facing activity
 * name is retained, while the implementation intentionally avoids ambiguous historical activity
 * ordinals and shows only unknown, still, and moving.
 */
fun activityRibbon(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.Segments> =
	mapViz("activity_ribbon")
		.source(activitySource(repo))
		.aggregate(SegmentsAggregator())
		.gradientLine(colorStops = ACTIVITY_RIBBON_RAMP, widthDp = 6f)
