package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.graphics.GridAggregator
import com.adsamcik.tracker.map.presentation.bridge.LayerAnimation
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig

/**
 * Supported native fallback for shader-like signal rendering: a broad translucent halo beneath a
 * tighter bright core. Both layers reuse one static GeoJSON field and animate only layer properties.
 */
class AuroraEncoder(
	private val haloColorStops: List<Pair<Float, Int>>,
	private val coreColorStops: List<Pair<Float, Int>>,
	private val haloRadiusPx: Float = 46f,
	private val coreRadiusPx: Float = 21f,
) : Encoder<SpatialData.WeightedCells> {

	override fun encode(field: SpatialData.WeightedCells, ctx: RenderContext): MapLibreLayerConfig {
		val geoJson = GeoJsonConverter.pointsToFeatureCollection(field.cells)
		return MapLibreLayerConfig.Composite(
			listOf(
				MapLibreLayerConfig.Heatmap(
					geoJson = geoJson,
					colorStops = haloColorStops,
					radiusPx = GridAggregator.radiusForQuality(haloRadiusPx, ctx.quality),
					intensity = 0.72f,
					opacity = 0.58f,
					animation = LayerAnimation.Pulse(
						periodMs = 3_600,
						minScale = 0.9f,
						maxScale = 1.12f,
					),
				),
				MapLibreLayerConfig.Heatmap(
					geoJson = geoJson,
					colorStops = coreColorStops,
					radiusPx = GridAggregator.radiusForQuality(coreRadiusPx, ctx.quality),
					intensity = 1.25f,
					opacity = 0.9f,
					animation = LayerAnimation.Pulse(
						periodMs = 2_900,
						minScale = 0.96f,
						maxScale = 1.08f,
					),
				),
			),
		)
	}
}

fun <Feature> EncodeStep<Feature, SpatialData.WeightedCells>.aurora(
	haloColorStops: List<Pair<Float, Int>>,
	coreColorStops: List<Pair<Float, Int>>,
): VizPipeline<Feature, SpatialData.WeightedCells> =
	encode(AuroraEncoder(haloColorStops, coreColorStops))
