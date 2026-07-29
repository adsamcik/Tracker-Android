package com.adsamcik.tracker.map.layers.impl

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoJsonConverter
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.map.perf.PerformanceManager
import com.adsamcik.tracker.map.presentation.bridge.MapLibreLayerConfig
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.map.shared.CoordinateBounds
import com.adsamcik.tracker.stats.api.roadmatch.RoadLimitProvenance

/**
 * Map layer that colours the roads a vehicle actually drove on by how the
 * recorded speed compared to that road's speed limit.
 *
 * Unlike a raw-GPS overlay, the geometry here is **road-matched**: an upstream
 * HMM/Viterbi map matcher (see `:stats-api` `RoadMatcher`) snaps the drive onto
 * the locally imported OSM road graph and produces [ComplianceEdge]s that follow
 * the real road centre-line. This layer only classifies each edge into a
 * [ComplianceBucket] and stitches contiguous same-bucket edges into continuous
 * strokes.
 *
 * Strictly offline: the [edgeProvider] streams from Room + the local OSM graph,
 * and bucket classification is a pure function. No network calls.
 *
 * The result is a [MapLibreLayerConfig.Composite] of up to 5
 * [MapLibreLayerConfig.Line]s — one per bucket — because the renderer applies a
 * single colour per Line config.
 */
open class VehicleComplianceLayer(
	private val resultProvider: suspend (LongRange) -> ProviderResult,
	private val perf: PerformanceManager = PerformanceManager(),
) : BaseMapLayer<VehicleComplianceLayer.Input, VehicleComplianceLayer.Prepared>(perf),
	SupportsDateRange {

	/**
	 * Compatibility constructor for callers that have no unavailable-result
	 * diagnostics to preserve. Production uses [ProviderResult] directly.
	 */
	constructor(
		edgeProvider: suspend (LongRange) -> List<ComplianceEdge>,
		perf: PerformanceManager = PerformanceManager(),
		@Suppress("UNUSED_PARAMETER") legacyConstructorMarker: Unit = Unit,
	) : this(resultProvider = { ProviderResult(edgeProvider(it)) }, perf = perf)

	/**
	 * One road-matched span ready for classification.
	 *
	 * [ratio] is `speed / speed-limit` for the span. [path] follows the matched
	 * road centre-line (>= 2 points). [gapBefore] is `true` when this edge does
	 * not continue directly from the previous edge (an unmatched stretch was
	 * skipped), which forces the start of a new visual stroke.
	 */
	data class ComplianceEdge(
		val ratio: Float,
		val path: List<LatLngModel>,
		val gapBefore: Boolean,
	)

	/** Why an observed span was intentionally withheld from a normal bucket. */
	enum class SuppressionReason {
		NO_MATCH,
		NO_PATH,
		GAP,
		AMBIGUOUS_MATCH,
		INVALID_EDGE_INDEX,
		INVALID_PATH,
		INVALID_SPEED,
		INVALID_LIMIT,
		UNKNOWN_LIMIT,
		HEURISTIC_LIMIT,
		UNSUPPORTED_LIMIT,
	}

	/**
	 * Bounded metadata for an unavailable/suppressed result. It intentionally
	 * contains only local graph and observation references, never raw GPS data.
	 */
	data class SuppressionDiagnostic(
		val reason: SuppressionReason,
		val importId: Long? = null,
		val osmWayId: Long? = null,
		val fromObservationIndex: Int? = null,
		val toObservationIndex: Int? = null,
		val limitKmh: Int? = null,
		val limitProvenance: RoadLimitProvenance? = null,
	)

	/** Result from the production provider, including deliberately withheld spans. */
	data class ProviderResult(
		val edges: List<ComplianceEdge>,
		val diagnostics: List<SuppressionDiagnostic> = emptyList(),
	)

	data class Input(
		val edges: List<ComplianceEdge>,
		val diagnostics: List<SuppressionDiagnostic> = emptyList(),
	)
	data class Prepared(
		val perBucket: List<BucketGeoJson>,
		val bounds: CoordinateBounds?,
		val diagnostics: List<SuppressionDiagnostic> = emptyList(),
	)

	data class BucketGeoJson(
		val bucket: ComplianceBucket,
		val geoJson: String,
	)

	override var dateRange: LongRange = LongRange(0, Long.MAX_VALUE)

	override suspend fun loadData(context: Context, bounds: Bounds?): Input {
		return resultProvider(dateRange).let { Input(it.edges, it.diagnostics) }
	}

	override fun processData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared {
		if (input.edges.isEmpty()) return Prepared(emptyList(), null, input.diagnostics)

		// Stitch contiguous same-bucket edges into continuous strokes. A new
		// stroke begins when the bucket changes or an unmatched stretch was
		// skipped (gapBefore). Consecutive contiguous edges share their boundary
		// point, so we drop the duplicate when concatenating.
		val runsByBucket = LinkedHashMap<ComplianceBucket, MutableList<List<LatLngModel>>>()
		var currentBucket: ComplianceBucket? = null
		var currentRun: MutableList<LatLngModel>? = null

		fun flush() {
			val run = currentRun
			val bucket = currentBucket
			if (run != null && bucket != null && run.size >= 2) {
				runsByBucket.getOrPut(bucket) { mutableListOf() }.add(run.toList())
			}
		}

		for (edge in input.edges) {
			if (edge.path.size < 2) {
				flush()
				currentBucket = null
				currentRun = null
				continue
			}
			val bucket = ComplianceBucket.forRatio(edge.ratio)
			if (bucket == null) {
				// An invalid value must not bridge two otherwise contiguous normal runs.
				flush()
				currentBucket = null
				currentRun = null
				continue
			}
			val startsNewRun = bucket != currentBucket || edge.gapBefore || currentRun == null
			if (startsNewRun) {
				flush()
				currentRun = edge.path.toMutableList()
				currentBucket = bucket
			} else {
				// Contiguous continuation: skip the shared boundary point.
				val run = currentRun!!
				val continuation = if (run.isNotEmpty() && run.last() == edge.path.first()) {
					edge.path.drop(1)
				} else {
					edge.path
				}
				run.addAll(continuation)
			}
		}
		flush()

		val perBucket = ComplianceBucket.entries.mapNotNull { bucket ->
			val segments = runsByBucket[bucket]?.filter { it.size >= 2 }
			if (segments.isNullOrEmpty()) {
				null
			} else {
				BucketGeoJson(bucket, GeoJsonConverter.segmentsToFeatureCollection(segments))
			}
		}

		val bounds = runsByBucket.values.asSequence()
			.flatMap { it.asSequence() }
			.flatMap { it.asSequence() }
			.toList()
			.coordinateBoundsOrNull()
		return Prepared(perBucket, bounds, input.diagnostics)
	}

	override fun produceConfig(processed: Prepared): MapLibreLayerConfig? {
		if (processed.perBucket.isEmpty()) return null
		val firstBounds = processed.bounds
		val lines = processed.perBucket.map { bucket ->
			MapLibreLayerConfig.Line(
				geoJson = bucket.geoJson,
				colorArgb = bucket.bucket.colorArgb,
				widthDp = LINE_WIDTH_DP,
				opacity = 1f,
				bounds = firstBounds,
			)
		}
		return MapLibreLayerConfig.Composite(layers = lines)
	}

	private fun List<LatLngModel>.coordinateBoundsOrNull(): CoordinateBounds? {
		if (isEmpty()) return null
		var minLat = Double.MAX_VALUE
		var maxLat = -Double.MAX_VALUE
		var minLng = Double.MAX_VALUE
		var maxLng = -Double.MAX_VALUE
		forEach { point ->
			minLat = minOf(minLat, point.lat)
			maxLat = maxOf(maxLat, point.lat)
			minLng = minOf(minLng, point.lng)
			maxLng = maxOf(maxLng, point.lng)
		}
		return CoordinateBounds(
			topBound = maxLat,
			rightBound = maxLng,
			bottomBound = minLat,
			leftBound = minLng,
		)
	}

	companion object {
		private const val LINE_WIDTH_DP = 4f
	}
}

/**
 * Speed-vs-baseline compliance buckets used by [VehicleComplianceLayer].
 *
 * Ratio = sample speed / baseline speed limit. Buckets are inclusive of the lower
 * bound and exclusive of the upper bound. Invalid values deliberately have no
 * bucket: an unavailable limit must not look like ordinary compliance.
 */
enum class ComplianceBucket(
	val colorArgb: Int,
	val minRatioInclusive: Float,
	val maxRatioExclusive: Float,
) {
	WAY_UNDER(HeatmapColorRamps.VehicleCompliance[0].second, Float.NEGATIVE_INFINITY, 0.5f),
	SLOW(HeatmapColorRamps.VehicleCompliance[1].second, 0.5f, 0.9f),
	AT_LIMIT(HeatmapColorRamps.VehicleCompliance[2].second, 0.9f, 1.1f),
	SLIGHTLY_OVER(HeatmapColorRamps.VehicleCompliance[3].second, 1.1f, 1.3f),
	SPEEDING(HeatmapColorRamps.VehicleCompliance[4].second, 1.3f, Float.POSITIVE_INFINITY);

	companion object {
		fun forRatio(ratio: Float): ComplianceBucket? {
			if (!ratio.isFinite() || ratio < 0f) return null
			return entries.firstOrNull { ratio >= it.minRatioInclusive && ratio < it.maxRatioExclusive }
		}
	}
}
