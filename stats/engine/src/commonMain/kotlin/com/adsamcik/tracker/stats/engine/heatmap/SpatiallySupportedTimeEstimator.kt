package com.adsamcik.tracker.stats.engine.heatmap

private fun requireRepresentableDuration(startMs: Long, endMsExclusive: Long) {
	require(endMsExclusive > startMs)
	require(startMs >= 0L || endMsExclusive <= Long.MAX_VALUE + startMs) {
		"Time range is too large to represent as a millisecond duration"
	}
}

private fun exactNonNegativeSum(left: Long, right: Long): Long {
	require(left >= 0L && right >= 0L)
	require(left <= Long.MAX_VALUE - right) { "Millisecond duration overflow" }
	return left + right
}

private fun exactDurationSum(intervals: Iterable<SpatialSupportInterval>): Long =
	intervals.fold(0L) { total, interval -> exactNonNegativeSum(total, interval.durationMs) }

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
		requireRepresentableDuration(startMs, endMsExclusive)
	}
}

/** A finite tracker-active span on the canonical timeline. */
data class TrackerActiveSpan(
	val startMs: Long,
	val endMsExclusive: Long,
	val clockDomainId: Long,
) {
	init {
		requireRepresentableDuration(startMs, endMsExclusive)
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
	val clockDomainId: Long?

	val durationMs: Long get() = endMsExclusive - startMs

	data class Supported(
		override val startMs: Long,
		override val endMsExclusive: Long,
		override val clockDomainId: Long,
		val kernel: NormalizedCompactKernel,
		val sourceEvidenceId: Long,
	) : SpatialSupportInterval {
		init {
			requireRepresentableDuration(startMs, endMsExclusive)
		}
	}

	data class Unresolved(
		override val startMs: Long,
		override val endMsExclusive: Long,
		override val clockDomainId: Long?,
	) : SpatialSupportInterval {
		init {
			requireRepresentableDuration(startMs, endMsExclusive)
		}
	}
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
		require(exactNonNegativeSum(supportedMs, unresolvedMs) == canonicalTrackedMs)
		require(exactDurationSum(intervals) == canonicalTrackedMs)
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
		val canonicalSpans = buildCanonicalSpans(clippedByDomain.values.flatten())
		if (canonicalSpans.isEmpty()) return emptyResult()

		val candidates = buildCandidates(clippedByDomain, evidence)
		val intervals = partitionCanonicalTime(canonicalSpans, candidates)
		val supportedMs = intervals.filterIsInstance<SpatialSupportInterval.Supported>()
			.fold(0L) { total, interval -> exactNonNegativeSum(total, interval.durationMs) }
		val canonicalMs = canonicalSpans.fold(0L) { total, span ->
			exactNonNegativeSum(total, span.endMsExclusive - span.startMs)
		}
		val unresolvedMs = canonicalMs - supportedMs
		check(unresolvedMs >= 0L)
		check(exactDurationSum(intervals) == canonicalMs)
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
			.mapValues { (_, groups) -> groups.sortedBy(EvidenceGroup::timeMs) }

		val candidates = buildList {
			for ((clockDomainId, spans) in spansByDomain) {
				val domainGroups = groupsByDomain[clockDomainId].orEmpty()
				for (span in spans) {
					val firstIndex = domainGroups.lowerBound(span.startMs)
					val endIndexExclusive = domainGroups.lowerBound(span.endMsExclusive)
					for (index in firstIndex until endIndexExclusive) {
						val group = domainGroups[index]
						val previous = if (index > firstIndex) domainGroups[index - 1] else null
						val next = if (index + 1 < endIndexExclusive) domainGroups[index + 1] else null
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
						if (end > start) {
							add(SupportCandidate(start, end, clockDomainId, group.selected))
						}
					}
				}
			}
		}.sortedWith(SUPPORT_CANDIDATE_TIME_ORDER)
		// Midpoint bounds make candidates within one clock domain mutually exclusive. Keeping this
		// invariant explicit lets partitioning stream them instead of re-scanning every candidate at
		// every boundary.
		checkCandidatesDoNotOverlap(candidates)
		return candidates
	}

	/** Conflicting same-time alternatives are unresolved instead of each receiving full time. */
	private fun selectCompatibleEvidence(group: List<LocationEvidence>): SelectedEvidence? {
		var kernel: NormalizedCompactKernel? = null
		var selected: SelectedEvidence? = null
		for (item in group) {
			val candidateKernel = kernelPolicy.kernelFor(item) ?: continue
			val candidate = SelectedEvidence(item, candidateKernel)
			when (val firstKernel = kernel) {
				null -> {
					kernel = candidateKernel
					selected = candidate
				}
				else -> {
					if (candidateKernel != firstKernel) return null
					if (checkNotNull(selected).let { SELECTED_EVIDENCE_ORDER.compare(candidate, it) < 0 }) {
						selected = candidate
					}
				}
			}
		}
		return selected
	}

	private fun partitionCanonicalTime(
		canonicalSpans: List<CanonicalSpan>,
		candidates: List<SupportCandidate>,
	): List<SpatialSupportInterval> {
		val candidatesByDomain = candidates.groupBy(SupportCandidate::clockDomainId)
		val result = ArrayList<SpatialSupportInterval>()
		for (span in canonicalSpans) {
			val clockDomainId = span.clockDomainId
			if (clockDomainId == null) {
				appendMerged(result, SpatialSupportInterval.Unresolved(span.startMs, span.endMsExclusive, null))
				continue
			}
			val domainCandidates = candidatesByDomain[clockDomainId].orEmpty()
			var cursor = span.startMs
			var candidateIndex = domainCandidates.firstEndingAfter(span.startMs)
			while (candidateIndex < domainCandidates.size) {
				val candidate = domainCandidates[candidateIndex]
				if (candidate.startMs >= span.endMsExclusive) break
				val supportedStart = maxOf(cursor, candidate.startMs, span.startMs)
				val supportedEnd = minOf(candidate.endMsExclusive, span.endMsExclusive)
				if (supportedEnd > supportedStart) {
					if (cursor < supportedStart) {
						appendMerged(
							result,
							SpatialSupportInterval.Unresolved(cursor, supportedStart, clockDomainId),
						)
					}
					appendMerged(
						result,
						SpatialSupportInterval.Supported(
							startMs = supportedStart,
							endMsExclusive = supportedEnd,
							clockDomainId = clockDomainId,
							kernel = candidate.selected.kernel,
							sourceEvidenceId = candidate.selected.evidence.stableId,
						),
					)
					cursor = supportedEnd
				}
				candidateIndex++
			}
			if (cursor < span.endMsExclusive) {
				appendMerged(
					result,
					SpatialSupportInterval.Unresolved(cursor, span.endMsExclusive, clockDomainId),
				)
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
				previous.endMsExclusive == interval.startMs &&
				previous.clockDomainId == interval.clockDomainId ->
				previous.copy(endMsExclusive = interval.endMsExclusive)
			previous is SpatialSupportInterval.Supported && interval is SpatialSupportInterval.Supported &&
				previous.endMsExclusive == interval.startMs &&
				previous.clockDomainId == interval.clockDomainId &&
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
		val clockDomainId: Long,
		val selected: SelectedEvidence,
	)
	private data class CanonicalSpan(
		val startMs: Long,
		val endMsExclusive: Long,
		val clockDomainId: Long?,
	)
	private data class DomainBoundary(
		val timeMs: Long,
		val clockDomainId: Long,
		val delta: Int,
	)

	private companion object {
		val SELECTED_EVIDENCE_ORDER = compareBy<SelectedEvidence>(
			{ it.kernel.family },
			{ it.kernel.version },
			{ it.kernel.centerLatitudeDegrees },
			{ it.kernel.centerLongitudeDegrees },
			{ it.kernel.supportRadiusM },
			{ it.evidence.sourceQualityRank },
			{ it.evidence.stableId },
		)
		val SUPPORT_CANDIDATE_TIME_ORDER = compareBy<SupportCandidate>(
			{ it.clockDomainId },
			{ it.startMs },
			{ it.endMsExclusive },
			{ it.selected.kernel.supportRadiusM },
			{ it.selected.evidence.sourceQualityRank },
			{ it.selected.evidence.stableId },
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

		fun buildCanonicalSpans(spans: List<TrackerActiveSpan>): List<CanonicalSpan> {
			val boundaries = spans.flatMap { span ->
				listOf(
					DomainBoundary(span.startMs, span.clockDomainId, 1),
					DomainBoundary(span.endMsExclusive, span.clockDomainId, -1),
				)
			}.sortedWith(compareBy(DomainBoundary::timeMs, DomainBoundary::clockDomainId))
			val result = ArrayList<CanonicalSpan>()
			val activeDomainCounts = mutableMapOf<Long, Int>()
			var index = 0
			while (index < boundaries.size) {
				val timeMs = boundaries[index].timeMs
				while (index < boundaries.size && boundaries[index].timeMs == timeMs) {
					val boundary = boundaries[index++]
					val count = activeDomainCounts.getOrElse(boundary.clockDomainId) { 0 } + boundary.delta
					if (count == 0) activeDomainCounts.remove(boundary.clockDomainId)
					else activeDomainCounts[boundary.clockDomainId] = count
				}
				val nextTimeMs = boundaries.getOrNull(index)?.timeMs ?: continue
				if (nextTimeMs <= timeMs || activeDomainCounts.isEmpty()) continue
				val clockDomainId = activeDomainCounts.keys.singleOrNull()
				val previous = result.lastOrNull()
				if (previous != null && previous.endMsExclusive == timeMs &&
					previous.clockDomainId == clockDomainId
				) {
					result[result.lastIndex] = previous.copy(endMsExclusive = nextTimeMs)
				} else {
					result += CanonicalSpan(timeMs, nextTimeMs, clockDomainId)
				}
			}
			return result
		}

		fun midpoint(first: Long, second: Long): Long =
			(first and second) + ((first xor second) shr 1)

		fun saturatedAdd(value: Long, delta: Long): Long =
			if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

		fun saturatedSubtract(value: Long, delta: Long): Long =
			if (value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta

		fun List<EvidenceGroup>.lowerBound(timeMs: Long): Int {
			var low = 0
			var high = size
			while (low < high) {
				val middle = (low + high) ushr 1
				if (this[middle].timeMs < timeMs) low = middle + 1 else high = middle
			}
			return low
		}

		fun List<SupportCandidate>.firstEndingAfter(timeMs: Long): Int {
			var low = 0
			var high = size
			while (low < high) {
				val middle = (low + high) ushr 1
				if (this[middle].endMsExclusive <= timeMs) low = middle + 1 else high = middle
			}
			return low
		}

		fun checkCandidatesDoNotOverlap(candidates: List<SupportCandidate>) {
			var previous: SupportCandidate? = null
			for (candidate in candidates) {
				val prior = previous
				if (prior != null && prior.clockDomainId == candidate.clockDomainId) {
					check(prior.endMsExclusive <= candidate.startMs) {
						"Support candidates for one clock domain must not overlap"
					}
				}
				previous = candidate
			}
		}
	}
}
