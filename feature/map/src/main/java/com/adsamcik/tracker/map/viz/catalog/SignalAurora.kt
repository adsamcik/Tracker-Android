package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.CellWeighting
import com.adsamcik.tracker.map.viz.GridHeatmapAggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.aurora
import com.adsamcik.tracker.map.viz.mapViz

internal val AURORA_HALO_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0x007E57C2,
	0.18f to 0x557E57C2,
	0.45f to 0xAA5C6BC0.toInt(),
	0.72f to 0xCC26C6DA.toInt(),
	1.0f to 0xEE69F0AE.toInt(),
)

internal val AURORA_CORE_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0x0000E5FF,
	0.28f to 0x4400E5FF,
	0.55f to 0xCC00E5FF.toInt(),
	0.82f to 0xFF69F0AE.toInt(),
	1.0f to 0xFFFFFFFF.toInt(),
)

/** Signal strength as a pulsing native halo/core composite; no custom native shader required. */
fun signalAurora(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("signal_aurora")
		.source(cellSignalSource(repo))
		.aggregate(GridHeatmapAggregator(CellWeighting.Average))
		.aurora(
			haloColorStops = AURORA_HALO_RAMP,
			coreColorStops = AURORA_CORE_RAMP,
		)
