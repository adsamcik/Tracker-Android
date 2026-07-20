package com.adsamcik.tracker.stats.engine.heatmap

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure V1 contract for the analytical quantity behind the location-history heatmap.
 *
 * All times are on a canonical millisecond timeline prepared by the data adapter. They must not be
 * raw wall-clock values when a clock discontinuity occurred. [clockDomainId] prevents temporal
 * support from crossing reboot/session boundaries.
 */
object SpatiallySupportedTimeMetric {
	const val KIND: String = "SPATIALLY_SUPPORTED_TIME_V1"
}

data class HeatmapInstantRange(
	val startMs: Long,
	val endMsExclusive: Long,
) {
	init {
		require(endMsExclusive > startMs)
	}
}

/** A finite tracker-active span on the canonical timeline. */
data class TrackerActiveSpan(
	val startMs: Long,
	val endMsExclusive: Long,
	val clockDomainId: Long,
) {
	init {
		require(endMsExclusive > startMs)
	}
}

enum class LocationPrecision {
	PRECISE,
	APPROXIMATE,
	UNKNOWN,
}

/** Raw spatial evidence after acquisition time has been projected onto the canonical timeline. */
data class LocationEvidence(
	val stableId: Long,
	val timeMs: Long,
	val clockDomainId: Long,
	val latitudeDegrees: Double,
	val longitudeDegrees: Double,
	val reportedHorizontalAccuracyM: Double?,
	val precision: LocationPrecision,
	val isMock: Boolean = false,
	/** Lower values win only after spatial compatibility has been established. */
	val sourceQualityRank: Int = 0,
) {
	init {
		require(latitudeDegrees in -90.0..90.0)
		require(longitudeDegrees in -180.0..180.0)
		require(reportedHorizontalAccuracyM == null || reportedHorizontalAccuracyM >= 0.0)
	}
}

enum class CompactKernelFamily {
	/** Finite-support radial kernel. The exact evaluator is identified by [version]. */
	COMPACT_RADIAL,
}

/**
 * A normalized spatial kernel. Its integral over the world is one; the estimator therefore assigns
 * duration exactly once without making pixels or cells canonical.
 */
data class NormalizedCompactKernel(
	val centerLatitudeDegrees: Double,
	val centerLongitudeDegrees: Double,
	val supportRadiusM: Double,
	val family: CompactKernelFamily = CompactKernelFamily.COMPACT_RADIAL,
	val version: Int,
) {
	init {
		require(centerLatitudeDegrees in -90.0..90.0)
		require(centerLongitudeDegrees in -180.0..180.0)
		require(supportRadiusM.isFinite() && supportRadiusM > 0.0)
		require(version > 0)
	}
}

/**
 * Calibration and permission policy are deliberately outside the temporal estimator. Returning
 * null leaves the evidence unresolved. In particular, approximate-only evidence can remain
 * unresolved until a broad support model has been accepted.
 */
fun interface SpatialKernelPolicy {
	fun kernelFor(evidence: LocationEvidence): NormalizedCompactKernel?
}

sealed interface SpatialSupportInterval {
	val startMs: Long
	val endMsExclusive: Long

	val durationMs: Long get() = endMsExclusive - startMs

	data class Supported(
		override val startMs: Long,
		override val endMsExclusive: Long,
		val kernel: NormalizedCompactKernel,
		val sourceEvidenceId: Long,
	) : SpatialSupportInterval

	data class Unresolved(
		override val startMs: Long,
		override val endMsExclusive: Long,
	) : SpatialSupportInterval
}

data class SpatiallySupportedTimeResult(
	val metricKind: String = SpatiallySupportedTimeMetric.KIND,
	val intervals: List<SpatialSupportInterval>,
	val canonicalTrackedMs: Long,
	val supportedMs: Long,
	val unresolvedMs: Long,
) {
	init {
		require(supportedMs >= 0L && unresolvedMs >= 0L)
		require(supportedMs + unresolvedMs == canonicalTrackedMs)
		require(intervals.sumOf(SpatialSupportInterval::durationMs) == canonicalTrackedMs)
	}
}

data class SpatiallySupportedTimeConfig(
	val freshnessBeforeMs: Long,
	val freshnessAfterMs: Long,
) {
	init {
		require(freshnessBeforeMs >= 0L)
		require(freshnessAfterMs >= 0L)
		require(freshnessBeforeMs > 0L || freshnessAfterMs > 0L)
	}
}

/**
 * Deterministically partitions tracker-active time into supported and unresolved half-open spans.
 * It performs no movement interpolation, route matching, viewport filtering, or persistence.
 */
class SpatiallySupportedTimeEstimator(
	private val config: SpatiallySupportedTimeConfig,
	private val kernelPolicy: SpatialKernelPolicy,
) {
	fun estimate(
		request: HeatmapInstantRange,
		activeSpans: List<TrackerActiveSpan>,
		evidence: List<LocationEvidence>,
	): SpatiallySupportedTimeResult {
		val clippedByDomain = activeSpans
			.mapNotNull { it.clip(request) }
			.groupBy(TrackerActiveSpan::clockDomainId)
			.mapValues { (_, spans) -> mergeDomainSpans(spans) }
		val canonicalSpans = mergeCanonicalSpans(clippedByDomain.values.flatten())
		if (canonicalSpans.isEmpty()) return emptyResult()

		val candidates = buildCandidates(clippedByDomain, evidence)
		val intervals = partitionCanonicalTime(canonicalSpans, candidates)
		val supportedMs = intervals.filterIsInstance<SpatialSupportInterval.Supported>()
			.sumOf(SpatialSupportInterval.Supported::durationMs)
		val canonicalMs = canonicalSpans.sumOf { it.endMsExclusive - it.startMs }
		val unresolvedMs = canonicalMs - supportedMs
		check(unresolvedMs >= 0L)
		check(intervals.sumOf(SpatialSupportInterval::durationMs) == canonicalMs)
		return SpatiallySupportedTimeResult(
			intervals = intervals,
			canonicalTrackedMs = canonicalMs,
			supportedMs = supportedMs,
			unresolvedMs = unresolvedMs,
		)
	}

	private fun buildCandidates(
		spansByDomain: Map<Long, List<TrackerActiveSpan>>,
		evidence: List<LocationEvidence>,
	): List<SupportCandidate> {
		val groupsByDomain = evidence
			.asSequence()
			.filterNot(LocationEvidence::isMock)
			.groupBy { EvidenceInstant(it.clockDomainId, it.timeMs) }
			.mapValues { (_, group) -> selectCompatibleEvidence(group) }
			.filterValues { it != null }
			.mapValues { requireNotNull(it.value) }
			.entries
			.groupBy({ it.key.clockDomainId }, { EvidenceGroup(it.key.timeMs, it.value) })

		return buildList {
			for ((clockDomainId, spans) in spansByDomain) {
				val domainGroups = groupsByDomain[clockDomainId].orEmpty().sortedBy(EvidenceGroup::timeMs)
				for (span in spans) {
					val inSpan = domainGroups.filter { it.timeMs >= span.startMs && it.timeMs < span.endMsExclusive }
					for (index in inSpan.indices) {
						val group = inSpan[index]
						val previous = inSpan.getOrNull(index - 1)
						val next = inSpan.getOrNull(index + 1)
						val start = maxOf(
							span.startMs,
							saturatedSubtract(group.timeMs, config.freshnessBeforeMs),
							previous?.let { midpoint(it.timeMs, group.timeMs) } ?: Long.MIN_VALUE,
						)
						val end = minOf(
							span.endMsExclusive,
							saturatedAdd(group.timeMs, config.freshnessAfterMs),
							next?.let { midpoint(group.timeMs, it.timeMs) } ?: Long.MAX_VALUE,
						)
						if (end > start) add(SupportCandidate(start, end, group.selected))
					}
				}
			}
		}.sortedWith(SUPPORT_CANDIDATE_ORDER)
	}

	/** Conflicting same-time alternatives are unresolved instead of each receiving full time. */
	private fun selectCompatibleEvidence(group: List<LocationEvidence>): SelectedEvidence? {
		val placeable = group
			.asSequence()
			.mapNotNull { item -> kernelPolicy.kernelFor(item)?.let { SelectedEvidence(item, it) } }
			.distinctBy { selected ->
				listOf(
					selected.evidence.latitudeDegrees,
					selected.evidence.longitudeDegrees,
					selected.kernel.supportRadiusM,
				)
			}
			.sortedWith(SELECTED_EVIDENCE_ORDER)
			.toList()
		val selected = placeable.firstOrNull() ?: return null
		val compatible = placeable.all { alternative ->
			haversineMeters(
				selected.kernel.centerLatitudeDegrees,
				selected.kernel.centerLongitudeDegrees,
				alternative.kernel.centerLatitudeDegrees,
				alternative.kernel.centerLongitudeDegrees,
			) <= selected.kernel.supportRadiusM + alternative.kernel.supportRadiusM
		}
		return selected.takeIf { compatible }
	}

	private fun partitionCanonicalTime(
		canonicalSpans: List<CanonicalSpan>,
		candidates: List<SupportCandidate>,
	): List<SpatialSupportInterval> {
		val result = ArrayList<SpatialSupportInterval>()
		for (span in canonicalSpans) {
			val relevant = candidates.filter { it.endMsExclusive > span.startMs && it.startMs < span.endMsExclusive }
			val boundaries = buildSet {
				add(span.startMs)
				add(span.endMsExclusive)
				relevant.forEach {
					add(maxOf(span.startMs, it.startMs))
					add(minOf(span.endMsExclusive, it.endMsExclusive))
				}
			}.sorted()
			for (index in 0 until boundaries.lastIndex) {
				val start = boundaries[index]
				val end = boundaries[index + 1]
				if (end <= start) continue
				val winner = relevant
					.asSequence()
					.filter { it.startMs <= start && it.endMsExclusive >= end }
					.minWithOrNull(SUPPORT_CANDIDATE_ORDER)
				val interval = if (winner == null) {
					SpatialSupportInterval.Unresolved(start, end)
				} else {
					SpatialSupportInterval.Supported(
						startMs = start,
						endMsExclusive = end,
						kernel = winner.selected.kernel,
						sourceEvidenceId = winner.selected.evidence.stableId,
					)
				}
				appendMerged(result, interval)
			}
		}
		return result
	}

	private fun appendMerged(
		result: MutableList<SpatialSupportInterval>,
		interval: SpatialSupportInterval,
	) {
		val previous = result.lastOrNull()
		val merged = when {
			previous is SpatialSupportInterval.Unresolved && interval is SpatialSupportInterval.Unresolved &&
				previous.endMsExclusive == interval.startMs -> previous.copy(endMsExclusive = interval.endMsExclusive)
			previous is SpatialSupportInterval.Supported && interval is SpatialSupportInterval.Supported &&
				previous.endMsExclusive == interval.startMs &&
				previous.sourceEvidenceId == interval.sourceEvidenceId && previous.kernel == interval.kernel ->
				previous.copy(endMsExclusive = interval.endMsExclusive)
			else -> null
		}
		if (merged == null) result += interval else result[result.lastIndex] = merged
	}

	private fun emptyResult() = SpatiallySupportedTimeResult(
		intervals = emptyList(),
		canonicalTrackedMs = 0L,
		supportedMs = 0L,
		unresolvedMs = 0L,
	)

	private data class EvidenceInstant(val clockDomainId: Long, val timeMs: Long)
	private data class EvidenceGroup(val timeMs: Long, val selected: SelectedEvidence)
	private data class SelectedEvidence(
		val evidence: LocationEvidence,
		val kernel: NormalizedCompactKernel,
	)
	private data class SupportCandidate(
		val startMs: Long,
		val endMsExclusive: Long,
		val selected: SelectedEvidence,
	)
	private data class CanonicalSpan(val startMs: Long, val endMsExclusive: Long)

	private companion object {
		const val EARTH_RADIUS_M = 6_371_008.8

		val SELECTED_EVIDENCE_ORDER = compareBy<SelectedEvidence>(
			{ it.kernel.supportRadiusM },
			{ it.evidence.sourceQualityRank },
			{ it.evidence.stableId },
		)
		val SUPPORT_CANDIDATE_ORDER = compareBy<SupportCandidate>(
			{ it.selected.kernel.supportRadiusM },
			{ it.selected.evidence.sourceQualityRank },
			{ it.selected.evidence.stableId },
			{ it.startMs },
			{ it.endMsExclusive },
		)

		fun TrackerActiveSpan.clip(range: HeatmapInstantRange): TrackerActiveSpan? {
			val start = maxOf(startMs, range.startMs)
			val end = minOf(endMsExclusive, range.endMsExclusive)
			return if (end > start) copy(startMs = start, endMsExclusive = end) else null
		}

		fun mergeDomainSpans(spans: List<TrackerActiveSpan>): List<TrackerActiveSpan> {
			val sorted = spans.sortedWith(compareBy(TrackerActiveSpan::startMs, TrackerActiveSpan::endMsExclusive))
			val result = ArrayList<TrackerActiveSpan>()
			for (span in sorted) {
				val previous = result.lastOrNull()
				if (previous != null && span.startMs <= previous.endMsExclusive) {
					result[result.lastIndex] = previous.copy(
						endMsExclusive = maxOf(previous.endMsExclusive, span.endMsExclusive),
					)
				} else {
					result += span
				}
			}
			return result
		}

		fun mergeCanonicalSpans(spans: List<TrackerActiveSpan>): List<CanonicalSpan> {
			val sorted = spans.sortedWith(compareBy(TrackerActiveSpan::startMs, TrackerActiveSpan::endMsExclusive))
			val result = ArrayList<CanonicalSpan>()
			for (span in sorted) {
				val previous = result.lastOrNull()
				if (previous != null && span.startMs <= previous.endMsExclusive) {
					result[result.lastIndex] = previous.copy(
						endMsExclusive = maxOf(previous.endMsExclusive, span.endMsExclusive),
					)
				} else {
					result += CanonicalSpan(span.startMs, span.endMsExclusive)
				}
			}
			return result
		}

		fun midpoint(first: Long, second: Long): Long = first + (second - first) / 2L

		fun saturatedAdd(value: Long, delta: Long): Long =
			if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

		fun saturatedSubtract(value: Long, delta: Long): Long =
			if (value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta

		fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
			val lat1Rad = lat1 * kotlin.math.PI / 180.0
			val lat2Rad = lat2 * kotlin.math.PI / 180.0
			val deltaLat = lat2Rad - lat1Rad
			val rawDeltaLon = (lon2 - lon1) * kotlin.math.PI / 180.0
			val deltaLon = when {
				rawDeltaLon > kotlin.math.PI -> rawDeltaLon - 2.0 * kotlin.math.PI
				rawDeltaLon < -kotlin.math.PI -> rawDeltaLon + 2.0 * kotlin.math.PI
				else -> rawDeltaLon
			}
			val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
				cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
			return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
		}
	}
}
