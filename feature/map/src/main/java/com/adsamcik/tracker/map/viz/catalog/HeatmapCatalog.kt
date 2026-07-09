package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.layers.impl.HeatmapColorRamps
import com.adsamcik.tracker.map.viz.CellWeighting
import com.adsamcik.tracker.map.viz.GridHeatmapAggregator
import com.adsamcik.tracker.map.viz.LegacyTileAggregatorStage
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.WifiCellAggregator
import com.adsamcik.tracker.map.viz.fill
import com.adsamcik.tracker.map.viz.heatmap
import com.adsamcik.tracker.map.viz.mapViz

/**
 * Catalog of grid-heatmap visualizations, each authored declaratively with the engine DSL. A layer
 * differs from its siblings only in its [VizSource][com.adsamcik.tracker.map.viz.VizSource], its
 * [CellWeighting] (value-vs-mass) and its colour ramp / radius — the `Source -> Aggregator -> Encoder`
 * machinery is shared. Adding a new grid heatmap is a few declarative lines here, not a new class.
 */

/** Location-density heatmap: colour = how often you were somewhere (visit count, log-scaled). */
fun locationDensityHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("location_heatmap")
		.source(locationDensitySource(repo))
		.aggregate(GridHeatmapAggregator(CellWeighting.Density))
		.heatmap(colorStops = HeatmapColorRamps.LocationDensity, baseRadiusPx = 20f)

/** Speed heatmap: colour = mean speed travelled in a cell (value, not count). */
fun speedHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("speed_heatmap")
		.source(speedSource(repo))
		.aggregate(GridHeatmapAggregator(CellWeighting.Average))
		.heatmap(colorStops = HeatmapColorRamps.Speed, baseRadiusPx = 20f)

/** Cell-signal heatmap: colour = mean technology-normalised signal strength in a cell. */
fun cellSignalHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("cell_heatmap")
		.source(cellSignalSource(repo))
		.aggregate(GridHeatmapAggregator(CellWeighting.Average))
		.heatmap(colorStops = HeatmapColorRamps.CellSignal, baseRadiusPx = 25f)

/** Signal dead-zone heatmap: cell-signal with inverted weight, so weak/absent coverage reads hot. */
fun signalCoverageHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("signal_coverage")
		.source(cellSignalSource(repo, invert = true))
		.aggregate(GridHeatmapAggregator(CellWeighting.Average))
		.heatmap(colorStops = HeatmapColorRamps.SignalDeadZone, baseRadiusPx = 25f)

/** Wi-Fi signal heatmap: colour = mean Wi-Fi signal quality, on the Wi-Fi cell grid. */
fun wifiSignalHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("wifi_heatmap")
		.source(wifiSignalSource(repo))
		.aggregate(WifiCellAggregator(baseCellPerQuality = 0.00045, normalizeByMax = false))
		.heatmap(colorStops = WIFI_SIGNAL_RAMP, baseRadiusPx = 18f)

/** Wi-Fi access-point count heatmap (viewport-relative normalisation, preserved from the old layer). */
fun wifiCountHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.WeightedCells> =
	mapViz("wifi_count_heatmap")
		.source(wifiCountSource(repo))
		.aggregate(WifiCellAggregator(baseCellPerQuality = 0.0006, normalizeByMax = true))
		.heatmap(colorStops = WIFI_COUNT_RAMP, baseRadiusPx = 18f)

private val WIFI_SIGNAL_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0x00FF9800,
	0.18f to 0x66FFB74D,
	0.45f to 0xFFFF9800.toInt(),
	0.72f to 0xFFFF7043.toInt(),
	1.0f to 0xFFD50000.toInt(),
)

private val WIFI_COUNT_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0x001FC8FF,
	0.18f to 0x661FC8FF,
	0.42f to 0xFF1FC8FF.toInt(),
	0.7f to 0xFF3F51B5.toInt(),
	1.0f to 0xFF6A1B9A.toInt(),
)

/**
 * Legacy square-tile heatmap (easter egg): location density rendered as discrete ~25 m coloured
 * tiles instead of a smooth GPU heatmap. Uses the [Fill][com.adsamcik.tracker.map.viz.fill] shape,
 * proving the engine renders more than heatmaps.
 */
fun legacyTileHeatmap(repo: GeoRepository): VizPipeline<WeightedGeoFeature, SpatialData.FillCells> =
	mapViz("legacy_heatmap")
		.source(rawLocationSource(repo))
		.aggregate(LegacyTileAggregatorStage())
		.fill(
			colorStops = HeatmapColorRamps.LegacyTiles,
			opacity = 0.6f,
			// Faint dark border so individual tiles read as discrete cells (the server-tile look).
			outlineColorArgb = 0x33000000,
		)
