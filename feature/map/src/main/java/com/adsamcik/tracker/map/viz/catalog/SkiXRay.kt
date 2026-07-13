package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.R
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.SymbolAggregator
import com.adsamcik.tracker.map.viz.SymbolFeature
import com.adsamcik.tracker.map.viz.SymbolStyle
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.VizSource
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.map.viz.symbols
import com.adsamcik.tracker.shared.model.LocationSample
import com.adsamcik.tracker.shared.model.SkiRunSegment
import com.adsamcik.tracker.shared.model.SkiSegmentType
import com.adsamcik.tracker.stats.api.repository.LocationSampleRepository
import com.adsamcik.tracker.stats.api.repository.SkiRunSegmentRepository
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val SKI_ICON_DOWNHILL = "ski_downhill"
internal const val SKI_ICON_LIFT = "ski_lift"
internal const val SKI_ICON_WALK = "ski_walk"

private const val E7 = 1e7
private const val MIN_ANCHOR_TOLERANCE_MS = 2 * 60_000L

/** One batched route-sample query anchors each ski segment near its temporal midpoint. */
fun skiXRaySource(
	skiRepository: SkiRunSegmentRepository,
	locationRepository: LocationSampleRepository,
): VizSource<SymbolFeature> = VizSource { request ->
	val segments = skiRepository.getSegmentsByTimeRange(request.dateRange.first, request.dateRange.last)
	if (segments.isEmpty()) {
		emptyList()
	} else {
		val anchors = segments.map { segment ->
			val midpoint = (segment.startTimeMs + segment.endTimeMs) / 2L
			val tolerance = maxOf(
				MIN_ANCHOR_TOLERANCE_MS,
				(segment.endTimeMs - segment.startTimeMs).coerceAtLeast(0L) / 2L + 60_000L,
			)
			SegmentAnchor(segment, midpoint, tolerance)
		}
		anchors.toMergedWindows().flatMap { window ->
			val samples = locationRepository.getSamplesBetween(window.startMs, window.endMs)
				.filter { it.latE7 != null && it.lonE7 != null }
				.sortedBy(LocationSample::timeMs)
			window.anchors.mapNotNull { requestAnchor ->
				val anchor = samples.nearestTo(requestAnchor.midpointMs)
					?.takeIf { abs(it.timeMs - requestAnchor.midpointMs) <= requestAnchor.toleranceMs }
					?: return@mapNotNull null
				val lat = requireNotNull(anchor.latE7) / E7
				val lon = requireNotNull(anchor.lonE7) / E7
				if (request.bounds?.contains(lat, lon) == false) return@mapNotNull null
				requestAnchor.segment.toSymbol(lat, lon)
			}
		}
	}
}

private data class SegmentAnchor(
	val segment: SkiRunSegment,
	val midpointMs: Long,
	val toleranceMs: Long,
)

private data class AnchorWindow(
	val startMs: Long,
	var endMs: Long,
	val anchors: MutableList<SegmentAnchor>,
)

private fun List<SegmentAnchor>.toMergedWindows(): List<AnchorWindow> {
	val windows = mutableListOf<AnchorWindow>()
	sortedBy(SegmentAnchor::midpointMs).forEach { anchor ->
		val startMs = anchor.midpointMs - anchor.toleranceMs
		val endMs = anchor.midpointMs + anchor.toleranceMs
		val current = windows.lastOrNull()
		if (current != null && startMs <= current.endMs) {
			current.endMs = maxOf(current.endMs, endMs)
			current.anchors += anchor
		} else {
			windows += AnchorWindow(startMs, endMs, mutableListOf(anchor))
		}
	}
	return windows
}

private fun List<LocationSample>.nearestTo(timeMs: Long): LocationSample? {
	if (isEmpty()) return null
	var low = 0
	var high = size
	while (low < high) {
		val mid = (low + high) ushr 1
		if (this[mid].timeMs < timeMs) low = mid + 1 else high = mid
	}
	val after = getOrNull(low)
	val before = getOrNull(low - 1)
	return listOfNotNull(before, after).minByOrNull { abs(it.timeMs - timeMs) }
}

private fun SkiRunSegment.toSymbol(lat: Double, lon: Double): SymbolFeature = SymbolFeature(
	lat = lat,
	lon = lon,
	time = (startTimeMs + endTimeMs) / 2L,
	label = when (segmentType) {
		SkiSegmentType.DOWNHILL_RUN ->
			"Run ${runIndex + 1} | ${(avgSpeedMps * 3.6f).roundToInt()} km/h | ${verticalM.roundToInt()} m"
		SkiSegmentType.LIFT_UP ->
			"${liftType ?: "Lift"} | ${verticalM.roundToInt()} m"
		SkiSegmentType.IDLE -> "Pause"
		SkiSegmentType.WALK -> "Walk | ${distanceM.roundToInt()} m"
	},
	iconKey = when (segmentType) {
		SkiSegmentType.DOWNHILL_RUN -> SKI_ICON_DOWNHILL
		SkiSegmentType.LIFT_UP -> SKI_ICON_LIFT
		SkiSegmentType.IDLE,
		SkiSegmentType.WALK -> SKI_ICON_WALK
	},
	priority = when (segmentType) {
		SkiSegmentType.DOWNHILL_RUN -> 3f
		SkiSegmentType.LIFT_UP -> 2f
		SkiSegmentType.WALK -> 1f
		SkiSegmentType.IDLE -> 0f
	},
)

private fun Bounds.contains(lat: Double, lon: Double): Boolean =
	lat in south..north && lon in west..east

private val SKI_SYMBOL_STYLES = mapOf(
	SKI_ICON_DOWNHILL to SymbolStyle(
		iconRes = R.drawable.ic_ski_downhill,
		iconColorArgb = 0xFF29B6F6.toInt(),
	),
	SKI_ICON_LIFT to SymbolStyle(
		iconRes = R.drawable.ic_ski_lift,
		iconColorArgb = 0xFFFFB300.toInt(),
	),
	SKI_ICON_WALK to SymbolStyle(
		iconRes = R.drawable.ic_ski_walk,
		iconColorArgb = 0xFFAB47BC.toInt(),
	),
)

fun skiXRay(
	skiRepository: SkiRunSegmentRepository,
	locationRepository: LocationSampleRepository,
): VizPipeline<SymbolFeature, SpatialData.Symbols> =
	mapViz("ski_xray")
		.source(skiXRaySource(skiRepository, locationRepository))
		.aggregate(SymbolAggregator())
		.symbols(styles = SKI_SYMBOL_STYLES, iconSizeDp = 25f, textSizeSp = 11f)
