package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * The engine's fourth render shape: point markers drawn as circles ([SpatialData.Markers] ->
 * [MapLibreLayerConfig.Circle]). Unlike the density/tile shapes, markers render individual points
 * whose colour and radius are both driven by a per-feature weight — ideal for "important places"
 * visualizations where a busier place should read bigger and hotter.
 */

/**
 * **Aggregator**: passes weighted points through as circle markers, capped at [maxMarkers]
 * (heaviest first) so a huge history can't flood the renderer.
 */
class MarkerAggregator(
	private val maxMarkers: Int = 2_000,
) : Aggregator<WeightedGeoFeature, SpatialData.Markers> {
	override fun aggregate(features: List<WeightedGeoFeature>, ctx: AggContext): SpatialData.Markers {
		val capped = if (features.size > maxMarkers) {
			features.sortedByDescending { it.weight }.take(maxMarkers)
		} else {
			features
		}
		return SpatialData.Markers(capped)
	}
}

/**
 * **Encoder**: [SpatialData.Markers] + style -> a MapLibre circle config. Each marker is coloured by
 * its weight via [colorStops] and sized between [minRadiusDp] and [maxRadiusDp]. Returns null for an
 * empty field.
 */
class CircleEncoder(
	private val colorStops: List<Pair<Float, Int>>,
	private val minRadiusDp: Float,
	private val maxRadiusDp: Float,
	private val opacity: Float = 0.9f,
	private val strokeColorArgb: Int = 0xFFFFFFFF.toInt(),
	private val strokeWidthDp: Float = 1.5f,
	private val animation: com.adsamcik.tracker.map.presentation.bridge.LayerAnimation? = null,
) : Encoder<SpatialData.Markers> {

	override fun encode(field: SpatialData.Markers, ctx: RenderContext): MapLibreLayerConfig? {
		if (field.points.isEmpty()) return null
		return MapLibreLayerConfig.Circle(
			geoJson = GeoJsonConverter.pointsToFeatureCollection(field.points),
			colorStops = colorStops,
			minRadiusDp = minRadiusDp,
			maxRadiusDp = maxRadiusDp,
			opacity = opacity,
			strokeColorArgb = strokeColorArgb,
			strokeWidthDp = strokeWidthDp,
			weightProperty = "weight",
			animation = animation,
		)
	}
}

/**
 * DSL terminal for the circle-marker family. Available only when the pipeline's field type is
 * [SpatialData.Markers], so a circle encoder cannot be paired with any other aggregator output — the
 * mismatch is a compile error. Pass an [animation] for a render-time effect (e.g. a gentle pulse).
 */
fun <Feature> EncodeStep<Feature, SpatialData.Markers>.circles(
	colorStops: List<Pair<Float, Int>>,
	minRadiusDp: Float,
	maxRadiusDp: Float,
	opacity: Float = 0.9f,
	strokeColorArgb: Int = 0xFFFFFFFF.toInt(),
	strokeWidthDp: Float = 1.5f,
	animation: com.adsamcik.tracker.map.presentation.bridge.LayerAnimation? = null,
): VizPipeline<Feature, SpatialData.Markers> =
	encode(CircleEncoder(colorStops, minRadiusDp, maxRadiusDp, opacity, strokeColorArgb, strokeWidthDp, animation))
