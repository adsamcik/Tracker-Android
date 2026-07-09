package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.CoordinateBounds
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The engine's fifth render shape: an attributed polyline drawn as a single line whose colour flows
 * along its length ([SpatialData.Segments] -> [MapLibreLayerConfig.GradientLine]). Where the heatmap
 * family answers "where", a ribbon answers "how it changed along the way" — speed, altitude, or
 * activity painted continuously over the route. This is the shared shape behind every "ribbon"
 * visualization; a new one is just a different source + colour ramp.
 */

/**
 * **Aggregator**: orders weighted fixes into a path (by time) and, if it exceeds [maxPoints],
 * evenly down-samples while preserving the per-vertex weight and always keeping the real endpoints.
 * Even-stride sampling keeps the weight profile representative without inventing interpolated fixes.
 */
class SegmentsAggregator(
	private val maxPoints: Int = 2_000,
) : Aggregator<WeightedGeoFeature, SpatialData.Segments> {

	override fun aggregate(features: List<WeightedGeoFeature>, ctx: AggContext): SpatialData.Segments {
		if (features.size < 2) return SpatialData.Segments(features)
		val ordered = features.sortedBy { it.time }
		val budget = min(maxPoints, ctx.maxPoints.takeIf { it >= 2 } ?: maxPoints)
		val path = if (ordered.size > budget) downSampleKeepingWeights(ordered, budget) else ordered
		return SpatialData.Segments(path)
	}

	private fun downSampleKeepingWeights(
		points: List<WeightedGeoFeature>,
		budget: Int,
	): List<WeightedGeoFeature> {
		val step = (points.size - 1).toDouble() / (budget - 1)
		val out = ArrayList<WeightedGeoFeature>(budget)
		var acc = 0.0
		while (out.size < budget) {
			out.add(points[acc.toInt().coerceAtMost(points.lastIndex)])
			acc += step
		}
		if (out.last() !== points.last()) out[out.lastIndex] = points.last()
		return out
	}
}

/**
 * **Encoder**: [SpatialData.Segments] + style -> a MapLibre gradient-line config. Each vertex's
 * weight is resolved to a colour via [colorStops] and placed at its cumulative-distance fraction
 * along the path, so `line-gradient` interpolates a smooth colour flow between them. Returns null for
 * a path with fewer than two distinct vertices.
 */
class GradientLineEncoder(
	private val colorStops: List<Pair<Float, Int>>,
	private val widthDp: Float = 6f,
	private val opacity: Float = 1f,
	private val casingColorArgb: Int? = DEFAULT_RIBBON_CASING_COLOR,
	private val casingWidthDp: Float = 2.5f,
) : Encoder<SpatialData.Segments> {

	override fun encode(field: SpatialData.Segments, ctx: RenderContext): MapLibreLayerConfig? {
		val path = field.path
		if (path.size < 2) return null
		val stops = buildGradientStops(path, colorStops)
		if (stops.size < 2) return null
		return MapLibreLayerConfig.GradientLine(
			geoJson = GeoJsonConverter.weightedLineToFeatureCollection(path),
			gradientStops = stops,
			widthDp = widthDp,
			opacity = opacity,
			casingColorArgb = casingColorArgb,
			casingWidthDp = casingWidthDp,
			bounds = path.coordinateBoundsOrNull(),
		)
	}

	companion object {
		/** Translucent dark outline giving the ribbon depth over any basemap (matches the polyline casing). */
		const val DEFAULT_RIBBON_CASING_COLOR = 0x66002A66
	}
}

/**
 * DSL terminal for the gradient-ribbon family. Available only when the pipeline's field type is
 * [SpatialData.Segments], so a gradient-line encoder cannot be paired with any other aggregator
 * output — the mismatch is a compile error.
 */
fun <Feature> EncodeStep<Feature, SpatialData.Segments>.gradientLine(
	colorStops: List<Pair<Float, Int>>,
	widthDp: Float = 6f,
	opacity: Float = 1f,
	casingColorArgb: Int? = GradientLineEncoder.DEFAULT_RIBBON_CASING_COLOR,
	casingWidthDp: Float = 2.5f,
): VizPipeline<Feature, SpatialData.Segments> =
	encode(GradientLineEncoder(colorStops, widthDp, opacity, casingColorArgb, casingWidthDp))

/**
 * Builds `line-gradient` stops from a weighted path: each vertex's cumulative-distance fraction in
 * `[0, 1]` paired with its weight-resolved colour. Zero-length steps are collapsed so progress is
 * strictly increasing (a hard requirement of MapLibre's interpolate input), and the ends are pinned
 * to exactly 0 and 1.
 */
internal fun buildGradientStops(
	path: List<WeightedGeoFeature>,
	colorStops: List<Pair<Float, Int>>,
): List<Pair<Float, Int>> {
	val cumulative = DoubleArray(path.size)
	var total = 0.0
	for (i in 1 until path.size) {
		total += approxMeters(path[i - 1], path[i])
		cumulative[i] = total
	}
	if (total <= 0.0) return emptyList()

	val out = ArrayList<Pair<Float, Int>>(path.size)
	var lastProgress = -1f
	path.forEachIndexed { i, feature ->
		val progress = when (i) {
			0 -> 0f
			path.lastIndex -> 1f
			else -> (cumulative[i] / total).toFloat()
		}
		if (progress > lastProgress) {
			out.add(progress to sampleRamp(colorStops, feature.weight.toFloat()))
			lastProgress = progress
		}
	}
	return out
}

/**
 * Linearly interpolates an ARGB colour from a sorted `[stop, argb]` ramp at [t] in `[0, 1]`,
 * blending each channel. Values outside the ramp clamp to its ends.
 */
internal fun sampleRamp(colorStops: List<Pair<Float, Int>>, t: Float): Int {
	if (colorStops.isEmpty()) return 0
	if (colorStops.size == 1 || t <= colorStops.first().first) return colorStops.first().second
	if (t >= colorStops.last().first) return colorStops.last().second
	for (i in 1 until colorStops.size) {
		val (upperStop, upperColor) = colorStops[i]
		if (t <= upperStop) {
			val (lowerStop, lowerColor) = colorStops[i - 1]
			val span = (upperStop - lowerStop).takeIf { it > 0f } ?: return upperColor
			return lerpArgb(lowerColor, upperColor, (t - lowerStop) / span)
		}
	}
	return colorStops.last().second
}

private fun lerpArgb(from: Int, to: Int, f: Float): Int {
	val a = channel(from, 24) + ((channel(to, 24) - channel(from, 24)) * f).toInt()
	val r = channel(from, 16) + ((channel(to, 16) - channel(from, 16)) * f).toInt()
	val g = channel(from, 8) + ((channel(to, 8) - channel(from, 8)) * f).toInt()
	val b = channel(from, 0) + ((channel(to, 0) - channel(from, 0)) * f).toInt()
	return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF

/** Equirectangular metre approximation — accurate enough at track scale and far cheaper than haversine. */
private fun approxMeters(a: WeightedGeoFeature, b: WeightedGeoFeature): Double {
	val meanLatRad = Math.toRadians((a.lat + b.lat) / 2.0)
	val x = Math.toRadians(b.lon - a.lon) * cos(meanLatRad)
	val y = Math.toRadians(b.lat - a.lat)
	return sqrt(x * x + y * y) * EARTH_RADIUS_METERS
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

private fun List<WeightedGeoFeature>.coordinateBoundsOrNull(): CoordinateBounds? {
	if (isEmpty()) return null
	var minLat = Double.MAX_VALUE
	var maxLat = -Double.MAX_VALUE
	var minLon = Double.MAX_VALUE
	var maxLon = -Double.MAX_VALUE
	forEach {
		minLat = minOf(minLat, it.lat)
		maxLat = maxOf(maxLat, it.lat)
		minLon = minOf(minLon, it.lon)
		maxLon = maxOf(maxLon, it.lon)
	}
	return CoordinateBounds(topBound = maxLat, rightBound = maxLon, bottomBound = minLat, leftBound = minLon)
}
