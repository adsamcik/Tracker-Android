package com.adsamcik.tracker.map.viz

import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.data.haversineMeters
import com.adsamcik.tracker.map.graphics.GridAggregator
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/** How a temporal heat cell derives its final value. */
enum class TemporalHeatMetric {
	/** Accuracy-weighted independent visits/traversals, logarithmically mapped to `[0, 1]`. */
	VisitDensity,

	/** Time-weighted mean of the source values in the cell (for example normalized speed). */
	AverageValue,
}

/**
 * Produces heat that is independent of collection cadence and separates movement from stationary
 * observations. Consecutive valid fixes no more than [maxPathGapMs] apart form candidate paths;
 * stale fixes and stationary runs remain point heat cells.
 *
 * Density is based on independent visits rather than samples or dwell duration. Samples within the
 * same visit contribute no additional mass. After leaving, contribution recovers gradually with
 * elapsed time, so a quick return barely changes heat while another-day visits contribute almost
 * fully. Location confidence also weights each contribution, suppressing inaccurate jumps.
 */
class TemporalHeatmapAggregator(
	private val metric: TemporalHeatMetric,
	private val maxPathGapMs: Long = DEFAULT_MAX_PATH_GAP_MS,
	private val visitReference: Double = DEFAULT_VISIT_REFERENCE,
) : Aggregator<WeightedGeoFeature, SpatialData.HeatField> {

	init {
		require(maxPathGapMs > 0L) { "maxPathGapMs must be positive" }
		require(visitReference > 0.0) { "visitReference must be positive" }
	}

	override fun aggregate(
		features: List<WeightedGeoFeature>,
		ctx: AggContext,
	): SpatialData.HeatField {
		val ordered = validOrderedFeatures(features)
		if (ordered.isEmpty()) return SpatialData.HeatField(emptyList(), emptyList())

		val runs = splitContinuousRuns(ordered)
		val cellSize = GridAggregator.cellSizeForZoom(ctx.zoom, ctx.quality)
		val cells = LinkedHashMap<CellKey, CellStats>()

		runs.forEach { run ->
			val support = temporalSupportSeconds(run)
			run.forEachIndexed { index, feature ->
				val key = cellKey(feature, cellSize)
				cells.getOrPut(key) { CellStats() }.add(feature, support[index], metric)
			}
		}

		val heatByCell = cells.mapValues { (_, stats) ->
			stats.heat(metric, visitReference)
		}

		val candidates = ArrayList<CandidatePath>()
		val nonPathCellKeys = LinkedHashSet<CellKey>()
		runs.forEachIndexed { index, run ->
			val candidate = candidatePath(index, run, cellSize, heatByCell)
			if (candidate == null) {
				run.forEach { nonPathCellKeys += cellKey(it, cellSize) }
			} else {
				candidates += candidate
			}
		}

		val budget = ctx.maxPoints.coerceAtLeast(1)
		val totalCandidateVertices = candidates.sumOf { it.points.size }
		val needsFallbackPoints = nonPathCellKeys.isNotEmpty() || totalCandidateVertices > budget
		val pointReserve = when {
			candidates.isEmpty() || !needsFallbackPoints -> 0
			else -> minOf(
				max(1, budget / 4),
				(budget - MIN_PATH_VERTICES).coerceAtLeast(0),
			)
		}
		val pathBudget = if (budget >= MIN_PATH_VERTICES) budget - pointReserve else 0
		val selected = selectPaths(candidates, pathBudget, heatByCell, cellSize)

		val pointKeys = LinkedHashSet<CellKey>()
		pointKeys += nonPathCellKeys
		candidates
			.filterNot { it.id in selected.selectedIds }
			.forEach { pointKeys += it.cellKeys }
		// A selected segment is the sole representation of every cell it crosses.
		pointKeys.removeAll(selected.cellKeys)

		val pointBudget = (budget - selected.paths.sumOf { it.size }).coerceAtLeast(0)
		val points = pointKeys
			.asSequence()
			.mapNotNull { key ->
				val heat = heatByCell[key] ?: return@mapNotNull null
				if (heat <= 0.0) return@mapNotNull null
				cells.getValue(key).toFeature(heat)
			}
			.sortedByDescending { it.weight }
			.take(pointBudget)
			.toList()

		return SpatialData.HeatField(isolatedCells = points, paths = selected.paths)
	}

	private fun validOrderedFeatures(features: List<WeightedGeoFeature>): List<WeightedGeoFeature> {
		val sorted = features
			.asSequence()
			.filter {
				it.lat.isFinite() && it.lon.isFinite() && it.weight.isFinite() &&
					it.lat in -90.0..90.0 && it.lon in -180.0..180.0
			}
			.sortedBy { it.time }
			.toList()
		if (sorted.size < 2) return sorted

		// A timestamp represents one location fix. Collapse accidental persistence/query duplicates so
		// they cannot manufacture visits; for visit density retain the most accurate duplicate.
		val deduplicated = ArrayList<WeightedGeoFeature>(sorted.size)
		sorted.forEach { feature ->
			val previous = deduplicated.lastOrNull()
			if (previous?.time != feature.time) {
				deduplicated += feature
			} else if (metric == TemporalHeatMetric.VisitDensity && feature.weight > previous.weight) {
				deduplicated[deduplicated.lastIndex] = feature
			}
		}
		return deduplicated
	}

	private fun splitContinuousRuns(features: List<WeightedGeoFeature>): List<List<WeightedGeoFeature>> {
		val runs = ArrayList<List<WeightedGeoFeature>>()
		var current = ArrayList<WeightedGeoFeature>()
		features.forEach { feature ->
			val previous = current.lastOrNull()
			if (previous != null && !isContinuous(previous, feature)) {
				runs += current
				current = ArrayList()
			}
			current += feature
		}
		if (current.isNotEmpty()) runs += current
		return runs
	}

	private fun isContinuous(from: WeightedGeoFeature, to: WeightedGeoFeature): Boolean {
		val elapsedMs = to.time - from.time
		if (elapsedMs !in 1L..maxPathGapMs) return false
		// Never let GeoJSON draw the long way across the world at the antimeridian.
		if (abs(to.lon - from.lon) > 180.0) return false
		val distance = haversineMeters(from.lat, from.lon, to.lat, to.lon)
		val speedMps = distance / (elapsedMs / 1_000.0)
		return speedMps <= MAX_PLAUSIBLE_SPEED_MPS
	}

	private fun temporalSupportSeconds(run: List<WeightedGeoFeature>): DoubleArray {
		if (run.size == 1) return doubleArrayOf(ISOLATED_SAMPLE_SECONDS)
		val support = DoubleArray(run.size)
		for (index in 1 until run.size) {
			val halfGap = (run[index].time - run[index - 1].time) / 2_000.0
			support[index - 1] += halfGap
			support[index] += halfGap
		}
		return support
	}

	private fun candidatePath(
		id: Int,
		run: List<WeightedGeoFeature>,
		cellSize: Double,
		heatByCell: Map<CellKey, Double>,
	): CandidatePath? {
		if (run.size < MIN_PATH_VERTICES) return null
		val movementThreshold = movementThresholdMeters(run)
		val origin = run.first()
		val maxDisplacement = run.maxOf {
			haversineMeters(origin.lat, origin.lon, it.lat, it.lon)
		}
		val hasSpeedEvidence = metric != TemporalHeatMetric.AverageValue ||
			run.any { it.weight * SPEED_NORMALIZATION_MPS >= MIN_MOVING_SPEED_MPS }
		if (!hasSpeedEvidence || maxDisplacement < movementThreshold) return null

		val compacted = compactPath(run, (movementThreshold / 3.0).coerceIn(2.0, 25.0))
		if (compacted.size < MIN_PATH_VERTICES) return null
		val keys = run.mapTo(LinkedHashSet()) { cellKey(it, cellSize) }
		val score = keys.maxOfOrNull { heatByCell[it] ?: 0.0 } ?: 0.0
		if (score <= 0.0) return null
		return CandidatePath(id, compacted, keys, score, run.last().time)
	}

	private fun movementThresholdMeters(run: List<WeightedGeoFeature>): Double = when (metric) {
		TemporalHeatMetric.AverageValue -> MIN_VALUE_PATH_SPAN_METERS
		TemporalHeatMetric.VisitDensity -> {
			val estimatedAccuracy = run
				.map { (1.0 - it.weight.coerceIn(0.0, 1.0)) * LOCATION_CONFIDENCE_RANGE_METERS }
				.sorted()
			val medianAccuracy = estimatedAccuracy[estimatedAccuracy.size / 2]
			max(MIN_DENSITY_PATH_SPAN_METERS, medianAccuracy * ACCURACY_MOVEMENT_MULTIPLIER)
				.coerceAtMost(MAX_DENSITY_PATH_SPAN_METERS)
		}
	}

	private fun compactPath(
		path: List<WeightedGeoFeature>,
		minimumStepMeters: Double,
	): List<WeightedGeoFeature> {
		val compacted = ArrayList<WeightedGeoFeature>()
		compacted += path.first()
		for (index in 1 until path.lastIndex) {
			val previous = compacted.last()
			val point = path[index]
			if (haversineMeters(previous.lat, previous.lon, point.lat, point.lon) >= minimumStepMeters) {
				compacted += point
			}
		}
		val last = path.last()
		val previous = compacted.last()
		if (haversineMeters(previous.lat, previous.lon, last.lat, last.lon) >= MIN_LINE_EDGE_METERS) {
			compacted += last
		}
		return compacted
	}

	private fun selectPaths(
		candidates: List<CandidatePath>,
		vertexBudget: Int,
		heatByCell: Map<CellKey, Double>,
		cellSize: Double,
	): PathSelection {
		if (vertexBudget < MIN_PATH_VERTICES || candidates.isEmpty()) return PathSelection.EMPTY
		val selected = candidates
			.sortedWith(compareByDescending<CandidatePath> { it.score }.thenByDescending { it.newestTime })
			.take(vertexBudget / MIN_PATH_VERTICES)
		if (selected.isEmpty()) return PathSelection.EMPTY

		val allocations = IntArray(selected.size) { MIN_PATH_VERTICES }
		var remaining = vertexBudget - allocations.sum()
		while (remaining > 0) {
			var allocated = false
			for (index in selected.indices) {
				if (remaining == 0) break
				if (allocations[index] < selected[index].points.size) {
					allocations[index] += 1
					remaining -= 1
					allocated = true
				}
			}
			if (!allocated) break
		}

		val paths = selected.mapIndexed { index, candidate ->
			val sampled = downSample(candidate.points, allocations[index])
			sampled.map { point ->
				point.copy(weight = heatByCell[cellKey(point, cellSize)] ?: 0.0)
			}
		}
		return PathSelection(
			paths = paths,
			selectedIds = selected.mapTo(HashSet()) { it.id },
			cellKeys = selected.flatMapTo(HashSet()) { it.cellKeys },
		)
	}

	private fun downSample(
		points: List<WeightedGeoFeature>,
		budget: Int,
	): List<WeightedGeoFeature> {
		if (points.size <= budget) return points
		val step = (points.size - 1).toDouble() / (budget - 1)
		return List(budget) { index ->
			if (index == budget - 1) points.last() else points[(index * step).toInt()]
		}
	}

	private fun cellKey(feature: WeightedGeoFeature, cellSize: Double): CellKey {
		val normalizedLon = when {
			feature.lon >= 180.0 -> feature.lon - 360.0
			else -> feature.lon
		}
		return CellKey(
			latBucket = floor(feature.lat / cellSize).toLong(),
			lonBucket = floor(normalizedLon / cellSize).toLong(),
		)
	}

	private data class CellKey(val latBucket: Long, val lonBucket: Long)

	private class CellStats {
		private var latSum = 0.0
		private var lonSum = 0.0
		private var count = 0
		private var newestTime = Long.MIN_VALUE
		private var visitMass = 0.0
		private var lastCellObservationTime = Long.MIN_VALUE
		private var valueSeconds = 0.0
		private var supportSeconds = 0.0

		fun add(feature: WeightedGeoFeature, support: Double, metric: TemporalHeatMetric) {
			latSum += feature.lat
			lonSum += feature.lon
			count += 1
			newestTime = maxOf(newestTime, feature.time)
			when (metric) {
				TemporalHeatMetric.VisitDensity -> {
					val novelty = if (lastCellObservationTime == Long.MIN_VALUE) {
						1.0
					} else {
						val elapsedSinceCell = feature.time - lastCellObservationTime
						if (elapsedSinceCell <= SAME_VISIT_GAP_MS) 0.0 else {
							1.0 - exp(
								-(elapsedSinceCell - SAME_VISIT_GAP_MS).toDouble() /
									VISIT_RECOVERY_TIME_MS,
							)
						}
					}
					visitMass += novelty * feature.weight.coerceIn(0.0, 1.0)
					lastCellObservationTime = feature.time
				}
				TemporalHeatMetric.AverageValue -> {
					valueSeconds += support * feature.weight.coerceIn(0.0, 1.0)
					supportSeconds += support
				}
			}
		}

		fun heat(metric: TemporalHeatMetric, visitReference: Double): Double = when (metric) {
			TemporalHeatMetric.VisitDensity ->
				(ln(1.0 + visitMass) / ln(1.0 + visitReference)).coerceIn(0.0, 1.0)
			TemporalHeatMetric.AverageValue ->
				if (supportSeconds > 0.0) (valueSeconds / supportSeconds).coerceIn(0.0, 1.0) else 0.0
		}

		fun toFeature(heat: Double): WeightedGeoFeature = WeightedGeoFeature(
			lat = latSum / count,
			lon = lonSum / count,
			time = newestTime,
			weight = heat,
		)
	}

	private data class CandidatePath(
		val id: Int,
		val points: List<WeightedGeoFeature>,
		val cellKeys: Set<CellKey>,
		val score: Double,
		val newestTime: Long,
	)

	private data class PathSelection(
		val paths: List<List<WeightedGeoFeature>>,
		val selectedIds: Set<Int>,
		val cellKeys: Set<CellKey>,
	) {
		companion object {
			val EMPTY = PathSelection(emptyList(), emptySet(), emptySet())
		}
	}

	companion object {
		const val DEFAULT_MAX_PATH_GAP_MS = 20_000L
		const val DEFAULT_VISIT_REFERENCE = 20.0
		private const val ISOLATED_SAMPLE_SECONDS = 1.0
		private const val SAME_VISIT_GAP_MS = 30L * 60L * 1_000L
		private const val VISIT_RECOVERY_TIME_MS = 6.0 * 60.0 * 60.0 * 1_000.0
		private const val MAX_PLAUSIBLE_SPEED_MPS = 400.0
		private const val LOCATION_CONFIDENCE_RANGE_METERS = 50.0
		private const val ACCURACY_MOVEMENT_MULTIPLIER = 1.5
		private const val MIN_DENSITY_PATH_SPAN_METERS = 6.0
		private const val MAX_DENSITY_PATH_SPAN_METERS = 75.0
		private const val MIN_VALUE_PATH_SPAN_METERS = 3.0
		private const val MIN_LINE_EDGE_METERS = 0.5
		private const val MIN_MOVING_SPEED_MPS = 0.5
		private const val SPEED_NORMALIZATION_MPS = 30.0
		private const val MIN_PATH_VERTICES = 2
	}
}

/** Encodes isolated heat cells plus continuous weighted paths as a composite MapLibre layer. */
class TemporalHeatmapEncoder(
	private val colorStops: List<Pair<Float, Int>>,
	private val baseRadiusPx: Float,
	private val pointOpacity: Float = 0.8f,
	private val lineOpacity: Float = 0.95f,
) : Encoder<SpatialData.HeatField> {

	override fun encode(field: SpatialData.HeatField, ctx: RenderContext): MapLibreLayerConfig {
		val kernelRadius = GridAggregator.radiusForQuality(baseRadiusPx, ctx.quality)
		val coreRadius = (kernelRadius * POINT_CORE_RADIUS_FRACTION).coerceAtLeast(MINIMUM_CORE_RADIUS_DP)
		val layers = buildList {
			if (field.isolatedCells.isNotEmpty()) {
				val geoJson = GeoJsonConverter.pointsToFeatureCollection(field.isolatedCells)
				// Direct feature colour is essential: a native heatmap would sum nearby kernels and
				// recreate false red hotspots even after visit novelty has been calculated correctly.
				add(MapLibreLayerConfig.Circle(
					geoJson = geoJson,
					colorStops = colorStops,
					minRadiusDp = kernelRadius,
					maxRadiusDp = kernelRadius,
					opacity = pointOpacity * POINT_GLOW_OPACITY_MULTIPLIER,
					strokeColorArgb = 0,
					strokeWidthDp = 0f,
				))
				add(MapLibreLayerConfig.Circle(
					geoJson = geoJson,
					colorStops = colorStops,
					minRadiusDp = coreRadius,
					maxRadiusDp = coreRadius,
					opacity = pointOpacity,
					strokeColorArgb = 0,
					strokeWidthDp = 0f,
				))
			}
			if (field.paths.any { it.size >= 2 }) {
				add(MapLibreLayerConfig.HeatLine(
					geoJson = GeoJsonConverter.weightedSegmentsToFeatureCollection(
						paths = field.paths,
						mergeToleranceDegrees = SEGMENT_MERGE_TOLERANCE_DEGREES,
					),
					colorStops = colorStops,
					// Both widths are point diameters, keeping point and segment footprints equal.
					widthDp = coreRadius * 2f,
					opacity = lineOpacity,
					glowWidthDp = kernelRadius * 2f,
				))
			}
		}
		return MapLibreLayerConfig.Composite(layers)
	}

	companion object {
		private const val POINT_GLOW_OPACITY_MULTIPLIER = 0.35f
		private const val POINT_CORE_RADIUS_FRACTION = 0.28f
		private const val MINIMUM_CORE_RADIUS_DP = 2f
		private const val SEGMENT_MERGE_TOLERANCE_DEGREES = 0.00005
	}
}

/** DSL terminal for cadence-independent heat fields with recency-aware movement lines. */
fun <Feature> EncodeStep<Feature, SpatialData.HeatField>.temporalHeatmap(
	colorStops: List<Pair<Float, Int>>,
	baseRadiusPx: Float,
	pointOpacity: Float = 0.8f,
	lineOpacity: Float = 0.95f,
): VizPipeline<Feature, SpatialData.HeatField> = encode(
	TemporalHeatmapEncoder(
		colorStops,
		baseRadiusPx,
		pointOpacity,
		lineOpacity,
	),
)
