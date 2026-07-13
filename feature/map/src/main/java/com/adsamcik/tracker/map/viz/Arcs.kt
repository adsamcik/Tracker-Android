package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.presentation.bridge.LayerAnimation
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.bridge.buildFlowGradientStops
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.shared.CoordinateBounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** One directed journey before aggregation. [groupKey] identifies a stable route and mode. */
data class ArcFeature(
	val startLat: Double,
	val startLon: Double,
	val endLat: Double,
	val endLon: Double,
	val time: Long,
	val groupKey: String,
	val category: String,
)

/** One aggregated curved route ready for rendering. */
data class FlowArc(
	val points: List<LatLngModel>,
	val category: String,
	val frequency: Int,
	val weight: Float,
	val phaseOffset: Float,
)

internal data class ArcEnvelope(
	val minLat: Double,
	val maxLat: Double,
	val minLon: Double,
	val maxLon: Double,
)

private data class ArcCurveGeometry(
	val endLon: Double,
	val controlLat: Double,
	val controlLon: Double,
)

/** Stable category colours shared by the encoder and Trip Constellations legend. */
internal val ARC_CATEGORY_COLORS: Map<String, Int> = mapOf(
	"walk" to 0xFF66BB6A.toInt(),
	"bike" to 0xFF26C6DA.toInt(),
	"car" to 0xFFFFA726.toInt(),
	"transit" to 0xFFAB47BC.toInt(),
	"other" to 0xFF90A4AE.toInt(),
)

/** Groups repeated directed journeys and builds deterministic separated Bezier arcs. */
class ArcAggregator(
	private val maxArcs: Int = 120,
	private val curvePoints: Int = 32,
) : Aggregator<ArcFeature, SpatialData.Arcs> {

	init {
		require(maxArcs > 0)
		require(curvePoints >= 2)
	}

	override fun aggregate(features: List<ArcFeature>, ctx: AggContext): SpatialData.Arcs {
		val grouped = features.groupBy(ArcFeature::groupKey)
			.values
			.sortedWith(compareByDescending<List<ArcFeature>> { it.size }.thenByDescending { group ->
				group.maxOf(ArcFeature::time)
			})
			.take(minOf(maxArcs, ctx.maxPoints.coerceAtLeast(1)))
		val arcs = grouped.mapNotNull { group ->
			val representative = group.maxBy(ArcFeature::time)
			val points = buildCurve(representative) ?: return@mapNotNull null
			FlowArc(
				points = points,
				category = representative.category,
				frequency = group.size,
				weight = frequencyWeight(group.size),
				phaseOffset = stablePhase(representative.groupKey),
			)
		}
		return SpatialData.Arcs(arcs)
	}

	private fun buildCurve(feature: ArcFeature): List<LatLngModel>? {
		val geometry = feature.curveGeometry() ?: return null
		return List(curvePoints) { index ->
			val t = index.toDouble() / (curvePoints - 1)
			val oneMinusT = 1.0 - t
			LatLngModel(
				lat = oneMinusT * oneMinusT * feature.startLat +
					2.0 * oneMinusT * t * geometry.controlLat +
					t * t * feature.endLat,
				lng = oneMinusT * oneMinusT * feature.startLon +
					2.0 * oneMinusT * t * geometry.controlLon +
					t * t * geometry.endLon,
			)
		}
	}

	private fun frequencyWeight(count: Int): Float =
		(ln(1.0 + count) / ln(1.0 + FREQUENCY_REFERENCE)).toFloat().coerceIn(0f, 1f)

	private fun stablePhase(key: String): Float = abs(key.hashCode() % 1_000) / 1_000f

	companion object {
		private const val FREQUENCY_REFERENCE = 20.0
	}
}

internal fun ArcFeature.curveEnvelope(): ArcEnvelope? {
	val geometry = curveGeometry() ?: return null
	return ArcEnvelope(
		minLat = minOf(startLat, endLat, geometry.controlLat),
		maxLat = maxOf(startLat, endLat, geometry.controlLat),
		minLon = minOf(startLon, geometry.endLon, geometry.controlLon),
		maxLon = maxOf(startLon, geometry.endLon, geometry.controlLon),
	)
}

private fun ArcFeature.curveGeometry(): ArcCurveGeometry? {
	val meanLatRadians = Math.toRadians((startLat + endLat) / 2.0)
	val longitudeScale = abs(cos(meanLatRadians)).coerceAtLeast(0.1)
	val unwrappedEndLon = startLon + shortestLongitudeDelta(startLon, endLon)
	val deltaX = (unwrappedEndLon - startLon) * longitudeScale
	val deltaY = endLat - startLat
	val length = sqrt(deltaX * deltaX + deltaY * deltaY)
	if (length <= MIN_ARC_LENGTH_DEGREES) return null
	val lane = 0.16 + abs(category.hashCode() % 4) * 0.025
	val offset = length * lane
	val controlXOffset = -deltaY / length * offset
	val controlYOffset = deltaX / length * offset
	return ArcCurveGeometry(
		endLon = unwrappedEndLon,
		controlLat = (startLat + endLat) / 2.0 + controlYOffset,
		controlLon = (startLon + unwrappedEndLon) / 2.0 + controlXOffset / longitudeScale,
	)
}

private fun shortestLongitudeDelta(startLon: Double, endLon: Double): Double =
	(endLon - startLon + 540.0) % 360.0 - 180.0

private const val MIN_ARC_LENGTH_DEGREES = 1e-12

/** Encodes every arc as a subdued route plus a moving comet highlight. */
class ArcEncoder(
	private val periodMs: Int = 4_200,
	private val trailFraction: Float = 0.2f,
) : Encoder<SpatialData.Arcs> {

	override fun encode(field: SpatialData.Arcs, ctx: RenderContext): MapLibreLayerConfig? {
		if (field.arcs.isEmpty()) return null
		val layers = field.arcs.flatMap { arc ->
			val color = ARC_CATEGORY_COLORS[arc.category] ?: requireNotNull(ARC_CATEGORY_COLORS["other"])
			val geoJson = GeoJsonConverter.lineToFeatureCollection(arc.points)
			val bounds = arc.points.coordinateBoundsOrNull()
			val width = 2.5f + arc.weight * 4f
			listOf(
				MapLibreLayerConfig.Line(
					geoJson = geoJson,
					colorArgb = color,
					widthDp = width,
					opacity = 0.42f,
					bounds = bounds,
					casingColorArgb = 0x44000000,
					casingWidthDp = 1.5f,
				),
				MapLibreLayerConfig.GradientLine(
					geoJson = geoJson,
					gradientStops = buildFlowGradientStops(color, arc.phaseOffset, trailFraction),
					widthDp = width + 1.5f,
					opacity = 1f,
					bounds = bounds,
					animation = LayerAnimation.Flow(
						periodMs = periodMs,
						colorArgb = color,
						trailFraction = trailFraction,
						phaseOffset = arc.phaseOffset,
					),
				),
			)
		}
		return MapLibreLayerConfig.Composite(layers)
	}
}

fun <Feature> EncodeStep<Feature, SpatialData.Arcs>.flowArcs(
	periodMs: Int = 4_200,
	trailFraction: Float = 0.2f,
): VizPipeline<Feature, SpatialData.Arcs> = encode(ArcEncoder(periodMs, trailFraction))

private fun List<LatLngModel>.coordinateBoundsOrNull(): CoordinateBounds? {
	if (isEmpty()) return null
	return CoordinateBounds(
		topBound = maxOf(LatLngModel::lat),
		rightBound = maxOf(LatLngModel::lng),
		bottomBound = minOf(LatLngModel::lat),
		leftBound = minOf(LatLngModel::lng),
	)
}
