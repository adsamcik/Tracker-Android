package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.graphics.LegacyTileAggregator
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * The engine's second render shape: discrete filled tiles ([SpatialData.FillCells] ->
 * [MapLibreLayerConfig.Fill]). Having a shape that is NOT a heatmap — with its own [fill] DSL
 * terminal constrained to [SpatialData.FillCells] — proves the type-linked design: a fill encoder
 * cannot be paired with a heatmap aggregator (or vice-versa) because the field types differ, so the
 * mismatch is a compile error rather than a runtime resolver check.
 */

/**
 * **Aggregator** for the legacy square-tile heatmap: raw fixes -> fixed ground-size grid tiles
 * (~25 m), each weighted by normalised visit count. The tile grid's longitude size is derived from
 * the data's mean latitude so tiles stay roughly square. Fixed tile size means quality has no effect.
 */
class LegacyTileAggregatorStage : Aggregator<WeightedGeoFeature, SpatialData.FillCells> {
	override fun aggregate(
		features: List<WeightedGeoFeature>,
		ctx: AggContext,
	): SpatialData.FillCells {
		if (features.isEmpty()) return SpatialData.FillCells(emptyList())
		val centerLat = features.sumOf { it.lat } / features.size
		return SpatialData.FillCells(LegacyTileAggregator.tile(points = features, centerLat = centerLat))
	}
}

/**
 * **Encoder** for filled-tile layers: [SpatialData.FillCells] + style -> a MapLibre fill config. Each
 * tile is coloured by its per-feature `weight` via [colorStops]; [outlineColorArgb] draws a faint
 * cell border so tiles read as discrete cells. Returns null for an empty field (nothing to render).
 */
class FillEncoder(
	private val colorStops: List<Pair<Float, Int>>,
	private val opacity: Float,
	private val outlineColorArgb: Int?,
) : Encoder<SpatialData.FillCells> {

	override fun encode(field: SpatialData.FillCells, ctx: RenderContext): MapLibreLayerConfig? {
		if (field.tiles.isEmpty()) return null
		return MapLibreLayerConfig.Fill(
			geoJson = GeoJsonConverter.tilesToFeatureCollection(field.tiles),
			colorStops = colorStops,
			opacity = opacity,
			outlineColorArgb = outlineColorArgb,
			weightProperty = "weight",
		)
	}
}

/**
 * DSL terminal for filled-tile layers. Available only when the pipeline's field type is
 * [SpatialData.FillCells], so it cannot be paired with a heatmap aggregator — the mismatch does not
 * compile (the compile-time counterpart of a runtime capability check).
 */
fun <Feature> EncodeStep<Feature, SpatialData.FillCells>.fill(
	colorStops: List<Pair<Float, Int>>,
	opacity: Float = 0.6f,
	outlineColorArgb: Int? = null,
): VizPipeline<Feature, SpatialData.FillCells> =
	encode(FillEncoder(colorStops, opacity, outlineColorArgb))
