package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.shared.CoordinateBounds
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The engine's fifth render shape: contiguous attributed polylines whose colour flows along each
 * path ([SpatialData.Segments] -> [MapLibreLayerConfig.GradientLine]). Where the heatmap
 * family answers "where", a ribbon answers "how it changed along the way" — speed, altitude, or
 * activity painted continuously over the route. This is the shared shape behind every "ribbon"
 * visualization; a new one is just a different source + colour ramp.
 */

/**
 * **Aggregator**: orders weighted fixes by time, splits on sampling gaps longer than [maxGapMs], and
 * evenly down-samples the resulting paths within [maxPoints]. Splitting prevents unrelated sessions
 * from being joined by synthetic map-spanning chords.
 */
class SegmentsAggregator(
	private val maxPoints: Int = 2_000,
	private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
	private val maxPaths: Int = DEFAULT_MAX_PATHS,
) : Aggregator<WeightedGeoFeature, SpatialData.Segments> {

	override fun aggregate(features: List<WeightedGeoFeature>, ctx: AggContext): SpatialData.Segments {
		if (features.size < 2) {
			return SpatialData.Segments(features.takeIf { it.isNotEmpty() }?.let(::listOf).orEmpty())
		}
		val ordered = features.orderedByTime()
		val budget = min(maxPoints, ctx.maxPoints.takeIf { it >= 2 } ?: maxPoints)
		val contiguous = splitOnGaps(ordered).filter { it.size >= 2 }
		if (contiguous.isEmpty()) return SpatialData.Segments(emptyList())
		return SpatialData.Segments(downSamplePaths(contiguous, budget))
	}

	private fun splitOnGaps(points: List<WeightedGeoFeature>): List<List<WeightedGeoFeature>> {
		val paths = mutableListOf<MutableList<WeightedGeoFeature>>()
		points.forEach { point ->
			val current = paths.lastOrNull()
			if (current == null || point.time - current.last().time > maxGapMs) {
				paths += mutableListOf(point)
			} else {
				current += point
			}
		}
		return paths
	}

	private fun downSamplePaths(
		paths: List<List<WeightedGeoFeature>>,
		budget: Int,
	): List<List<WeightedGeoFeature>> {
		val kept = paths.takeLast(minOf(maxPaths, (budget / 2).coerceAtLeast(1)))
		if (kept.sumOf { it.size } <= budget) return kept
		val baseBudget = (budget / kept.size).coerceAtLeast(2)
		val remainder = budget - baseBudget * kept.size
		return kept.mapIndexed { index, path ->
			val pathBudget = (baseBudget + if (index < remainder) 1 else 0).coerceAtMost(path.size)
			if (path.size > pathBudget) downSampleKeepingWeights(path, pathBudget) else path
		}
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

	companion object {
		internal const val DEFAULT_MAX_GAP_MS = 5 * 60_000L
		internal const val DEFAULT_MAX_PATHS = 64
	}
}

/**
 * **Encoder**: [SpatialData.Segments] + style -> one MapLibre gradient-line config per path. Each vertex's
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
		val configs = field.paths.mapNotNull(::encodePath)
		return when (configs.size) {
			0 -> null
			1 -> configs.single()
			else -> MapLibreLayerConfig.Composite(configs)
		}
	}

	private fun encodePath(path: List<WeightedGeoFeature>): MapLibreLayerConfig.GradientLine? {
		if (path.size < 2) return null
		val renderPath = path.unwrapLongitudes()
		val stops = buildGradientStops(renderPath, colorStops)
		if (stops.size < 2) return null
		return MapLibreLayerConfig.GradientLine(
			geoJson = GeoJsonConverter.weightedLineToFeatureCollection(renderPath),
			gradientStops = stops,
			widthDp = widthDp,
			opacity = opacity,
			casingColorArgb = casingColorArgb,
			casingWidthDp = casingWidthDp,
			bounds = renderPath.coordinateBoundsOrNull(),
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
	val x = Math.toRadians(shortestLongitudeDelta(a.lon, b.lon)) * cos(meanLatRad)
	val y = Math.toRadians(b.lat - a.lat)
	return sqrt(x * x + y * y) * EARTH_RADIUS_METERS
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

private fun List<WeightedGeoFeature>.orderedByTime(): List<WeightedGeoFeature> {
	for (index in 1 until size) {
		if (this[index - 1].time > this[index].time) {
			return sortedBy(WeightedGeoFeature::time)
		}
	}
	return this
}

private fun List<WeightedGeoFeature>.unwrapLongitudes(): List<WeightedGeoFeature> {
	if (size < 2) return this
	val unwrapped = ArrayList<WeightedGeoFeature>(size)
	unwrapped += first()
	var previousLongitude = first().lon
	for (index in 1 until size) {
		val point = this[index]
		val longitude = previousLongitude + shortestLongitudeDelta(previousLongitude, point.lon)
		unwrapped += if (longitude == point.lon) point else point.copy(lon = longitude)
		previousLongitude = longitude
	}
	return unwrapped
}

private fun shortestLongitudeDelta(from: Double, to: Double): Double {
	var delta = (to - from) % 360.0
	if (delta > 180.0) delta -= 360.0
	if (delta < -180.0) delta += 360.0
	return delta
}

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
