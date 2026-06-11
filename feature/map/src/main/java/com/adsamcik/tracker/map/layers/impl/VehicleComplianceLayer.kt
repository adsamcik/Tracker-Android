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

/**
 * Map layer that draws a polyline of driving-activity GPS samples coloured by how the
 * sample's speed compares to the active baseline speed limit. The polyline is rendered
 * as a [MapLibreLayerConfig.Composite] of up to 5 [MapLibreLayerConfig.Line]s — one
 * per speed-vs-baseline bucket — because the renderer applies a single colour per
 * Line config.
 *
 * The layer is offline by construction: the [sampleProvider] streams from Room and
 * the bucket lookup is a pure function. No network calls.
 */
open class VehicleComplianceLayer(
	private val sampleProvider: suspend (LongRange) -> List<VehicleSpeedSample>,
	private val perf: PerformanceManager = PerformanceManager(),
) : BaseMapLayer<VehicleComplianceLayer.Input, VehicleComplianceLayer.Prepared>(perf),
	SupportsDateRange {

	/** Decoded sample with bucket already classified. */
	data class VehicleSpeedSample(
		val latLng: LatLngModel,
		val ratio: Float,
	)

	data class Input(val samples: List<VehicleSpeedSample>)
	data class Prepared(
		val perBucket: List<BucketGeoJson>,
		val bounds: CoordinateBounds?,
	)

	data class BucketGeoJson(
		val bucket: ComplianceBucket,
		val geoJson: String,
	)

	override var dateRange: LongRange = LongRange(0, Long.MAX_VALUE)

	override suspend fun loadData(context: Context, bounds: Bounds?): Input {
		val samples = sampleProvider(dateRange)
		return Input(samples)
	}

	override fun processData(input: Input, budgets: PerformanceManager.PerformanceBudgets): Prepared {
		if (input.samples.isEmpty()) return Prepared(emptyList(), null)

		// Group samples into buckets. We split into contiguous runs per bucket so each
		// run renders as a single LineString — that keeps strokes visually continuous
		// while still letting each bucket use its own colour.
		val runs = mutableListOf<Pair<ComplianceBucket, MutableList<LatLngModel>>>()
		var currentBucket: ComplianceBucket? = null
		var currentRun: MutableList<LatLngModel>? = null
		var previousPoint: LatLngModel? = null

		for (sample in input.samples) {
			val bucket = ComplianceBucket.forRatio(sample.ratio)
			if (bucket == currentBucket && currentRun != null) {
				currentRun.add(sample.latLng)
			} else {
				// Stitch the new run to the previous point so segments visually connect
				// across bucket changes (no visual gap when colour switches).
				val run = mutableListOf<LatLngModel>()
				previousPoint?.let { run.add(it) }
				run.add(sample.latLng)
				runs += bucket to run
				currentBucket = bucket
				currentRun = run
			}
			previousPoint = sample.latLng
		}

		val grouped = runs.groupBy(
			keySelector = { it.first },
			valueTransform = { it.second.toList() },
		)

		val perBucket = ComplianceBucket.entries
			.mapNotNull { bucket ->
				val segments = grouped[bucket] ?: return@mapNotNull null
				val drawable = segments.filter { it.size >= 2 }
				if (drawable.isEmpty()) {
					null
				} else {
					BucketGeoJson(
						bucket = bucket,
						geoJson = GeoJsonConverter.segmentsToFeatureCollection(drawable),
					)
				}
			}

		val bounds = input.samples.asSequence().map { it.latLng }.toList().coordinateBoundsOrNull()
		return Prepared(perBucket, bounds)
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
 * bound and exclusive of the upper bound; [forRatio] guarantees a total mapping.
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
		fun forRatio(ratio: Float): ComplianceBucket {
			if (ratio.isNaN()) return AT_LIMIT
			return entries.first { ratio >= it.minRatioInclusive && ratio < it.maxRatioExclusive }
		}
	}
}
