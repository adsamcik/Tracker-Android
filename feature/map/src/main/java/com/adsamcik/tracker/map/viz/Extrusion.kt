package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * The engine's third render shape: 3D extruded polygons ([SpatialData.FillCells] ->
 * [MapLibreLayerConfig.FillExtrusion]). It reuses the tile field of the [fill] shape but lifts each
 * tile into the third dimension by its weight, so density becomes physical height — the "life as
 * terrain" look. Having [extrude] alongside [fill] on the same field type shows one shape can drive
 * several render styles while the type system still rejects heatmap<->fill/extrude mismatches.
 */

/**
 * **Encoder** for 3D terrain: [SpatialData.FillCells] + style -> a MapLibre fill-extrusion config.
 * Each tile is coloured by its weight via [colorStops] and extruded to `weight * maxHeightMeters`.
 * Returns null for an empty field (nothing to render).
 */
class ExtrusionEncoder(
	private val colorStops: List<Pair<Float, Int>>,
	private val maxHeightMeters: Float,
	private val opacity: Float = 0.85f,
) : Encoder<SpatialData.FillCells> {

	override fun encode(field: SpatialData.FillCells, ctx: RenderContext): MapLibreLayerConfig? {
		if (field.tiles.isEmpty()) return null
		return MapLibreLayerConfig.FillExtrusion(
			geoJson = GeoJsonConverter.tilesToFeatureCollection(field.tiles),
			colorStops = colorStops,
			maxHeightMeters = maxHeightMeters,
			opacity = opacity,
			weightProperty = "weight",
		)
	}
}

/**
 * DSL terminal for the 3D terrain family. Available only when the pipeline's field type is
 * [SpatialData.FillCells], so an extrusion encoder cannot be paired with any other aggregator output
 * — the mismatch is a compile error.
 */
fun <Feature> EncodeStep<Feature, SpatialData.FillCells>.extrude(
	colorStops: List<Pair<Float, Int>>,
	maxHeightMeters: Float,
	opacity: Float = 0.85f,
): VizPipeline<Feature, SpatialData.FillCells> =
	encode(ExtrusionEncoder(colorStops, maxHeightMeters, opacity))
