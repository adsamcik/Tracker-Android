package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.tracker.source.model.SourceEventId

internal data class ActivityCapturedCoalescingRequest(
	val mutation: ActivityCapturedWindowMutation,
	val observations: List<ActivityCapturedObservation>,
	val declaredGaps: List<ActivityCoverageGap> = emptyList(),
) {
	val authority: ActivityCaptureAuthority
		get() = mutation.identity.authority

	val intervalStartElapsedRealtimeNanos: Long
		get() = mutation.identity.intervalStartElapsedRealtimeNanos

	val intervalEndExclusiveElapsedRealtimeNanos: Long
		get() = mutation.identity.intervalEndExclusiveElapsedRealtimeNanos
}

internal enum class ActivityCoalescingRejection {
	TOO_MANY_OBSERVATIONS,
	TOO_MANY_DECLARED_GAPS,
	AUTHORITY_MISMATCH,
	OBSERVATION_AFTER_WINDOW,
	OBSERVATION_INSIDE_DECLARED_GAP,
	EVENT_IDENTITY_COLLISION,
	MALFORMED_DECLARED_GAP,
	WALL_TIME_DERIVATION_FAILED,
}

internal sealed interface ActivityCoalescingResult {
	data class Coalesced(
		val window: ActivityCapturedWindow,
	) : ActivityCoalescingResult

	data class Rejected(
		val reason: ActivityCoalescingRejection,
	) : ActivityCoalescingResult
}

/**
 * Pure, bounded Activity fact composition. Transitions always win where they provide coverage;
 * sampled classifications can only fill otherwise-uncovered time from a direct capture plan.
 */
internal object ActivityCapturedFactCoalescer {
	fun coalesce(request: ActivityCapturedCoalescingRequest): ActivityCoalescingResult {
		if (request.observations.size > MAX_OBSERVATIONS) {
			return rejected(ActivityCoalescingRejection.TOO_MANY_OBSERVATIONS)
		}
		if (request.declaredGaps.size > MAX_DECLARED_GAPS) {
			return rejected(ActivityCoalescingRejection.TOO_MANY_DECLARED_GAPS)
		}
		if (request.observations.any { it.authority != request.authority }) {
			return rejected(ActivityCoalescingRejection.AUTHORITY_MISMATCH)
		}
		if (request.observations.any {
				it.reference.providerElapsedRealtimeNanos >=
					request.intervalEndExclusiveElapsedRealtimeNanos
			}
		) return rejected(ActivityCoalescingRejection.OBSERVATION_AFTER_WINDOW)

		val gaps = request.declaredGaps.sortedWith(
			compareBy(
				ActivityCoverageGap::intervalStartElapsedRealtimeNanos,
				ActivityCoverageGap::intervalEndExclusiveElapsedRealtimeNanos,
				ActivityCoverageGap::reason,
			),
		)
		if (!gaps.areValidFor(request)) {
			return rejected(ActivityCoalescingRejection.MALFORMED_DECLARED_GAP)
		}
		if (request.observations.any { observation ->
				gaps.any { gap -> observation.reference.providerElapsedRealtimeNanos in gap }
			}
		) return rejected(ActivityCoalescingRejection.OBSERVATION_INSIDE_DECLARED_GAP)

		val canonical = canonicalize(request.observations)
		if (canonical is Canonicalization.IdentityCollision) {
			return rejected(ActivityCoalescingRejection.EVENT_IDENTITY_COLLISION)
		}
		canonical as Canonicalization.Success

		val transitionComposition = composeTransitions(
			request = request,
			observations = canonical.observations.filterIsInstance<
				ActivityCapturedObservation.Transition
			>(),
			gaps = gaps,
		)
		val sampled = canonical.observations.filterIsInstance<
			ActivityCapturedObservation.SampledClassification
		>()
		val rawBands = composeAllBands(
			request = request,
			transitionBands = transitionComposition.bands,
			sampled = sampled,
			gaps = gaps,
		)
		val bands = rawBands.mergeAdjacent().mapIndexed { fragmentOrdinal, raw ->
			raw.toCapturedBand(request.mutation, fragmentOrdinal, canonical.observations)
		}
		if (bands.any { it == null }) {
			return rejected(ActivityCoalescingRejection.WALL_TIME_DERIVATION_FAILED)
		}
		val capturedBands = bands.filterNotNull()
		val coveredGaps = complementWithDeclaredGaps(request, capturedBands, gaps)
		val usedSampleIds = rawBands.asSequence()
			.filter { it.mechanism == ActivityBandMechanism.SAMPLED_CLASSIFICATION }
			.flatMap { it.evidence.asSequence() }
			.map(ActivityCapturedObservationReference::sourceEventId)
			.toSet()
		val unchangedSamples = sampled.count { it.reference.sourceEventId !in usedSampleIds }

		return ActivityCoalescingResult.Coalesced(
			ActivityCapturedWindow(
				mutation = request.mutation,
				bands = capturedBands,
				gaps = coveredGaps,
				exactDuplicateCount = canonical.exactDuplicateCount,
				semanticDuplicateCount = canonical.semanticDuplicateCount,
				unchangedEvidenceCount = transitionComposition.unchangedCount + unchangedSamples,
			),
		)
	}

	private fun canonicalize(
		observations: List<ActivityCapturedObservation>,
	): Canonicalization {
		val byIdentity = linkedMapOf<SourceEventId, ActivityCapturedObservation>()
		var exactDuplicates = 0
		for (observation in observations.sortedWith(observationComparator)) {
			val previous = byIdentity[observation.reference.sourceEventId]
			when {
				previous == null -> byIdentity[observation.reference.sourceEventId] = observation
				previous == observation -> exactDuplicates += 1
				else -> return Canonicalization.IdentityCollision
			}
		}
		val byMeaning = linkedMapOf<ActivitySemanticKey, ActivityCapturedObservation>()
		var semanticDuplicates = 0
		for (observation in byIdentity.values) {
			val key = observation.semanticKey()
			if (key in byMeaning) {
				semanticDuplicates += 1
			} else {
				byMeaning[key] = observation
			}
		}
		return Canonicalization.Success(
			observations = byMeaning.values.toList(),
			exactDuplicateCount = exactDuplicates,
			semanticDuplicateCount = semanticDuplicates,
		)
	}

	private fun composeTransitions(
		request: ActivityCapturedCoalescingRequest,
		observations: List<ActivityCapturedObservation.Transition>,
		gaps: List<ActivityCoverageGap>,
	): TransitionComposition {
		val transitionsByTime = observations.groupBy {
			it.reference.providerElapsedRealtimeNanos
		}
		val state = mutableMapOf<CapturedActivityType, ActivityCapturedObservation.Transition>()
		var unchangedCount = 0
		transitionsByTime.keys.asSequence()
			.filter { it < request.intervalStartElapsedRealtimeNanos }
			.sorted()
			.forEach { time ->
				unchangedCount += applyTransitions(
					state,
					transitionsByTime.getValue(time),
				).unchangedCount
			}

		val boundaries = buildSet {
			add(request.intervalStartElapsedRealtimeNanos)
			add(request.intervalEndExclusiveElapsedRealtimeNanos)
			transitionsByTime.keys.filterTo(this) {
				it >= request.intervalStartElapsedRealtimeNanos
			}
			gaps.forEach { gap ->
				add(gap.intervalStartElapsedRealtimeNanos)
				add(gap.intervalEndExclusiveElapsedRealtimeNanos)
			}
		}.sorted()
		val bands = mutableListOf<RawActivityBand>()
		for (index in 0 until boundaries.lastIndex) {
			val start = boundaries[index]
			val end = boundaries[index + 1]
			if (gaps.any { it.intervalStartElapsedRealtimeNanos == start }) state.clear()
			val containingGap = gaps.firstOrNull { start in it }
			if (containingGap != null) continue
			val application = transitionsByTime[start]?.let { atStart ->
				applyTransitions(state, atStart)
			} ?: TransitionApplication.EMPTY
			unchangedCount += application.unchangedCount
			if (application.changedReferences.isNotEmpty() && bands.lastOrNull()?.end == start) {
				val previous = bands.last()
				bands[bands.lastIndex] = previous.copy(
					evidence = (previous.evidence + application.changedReferences)
						.distinctBy { it.sourceEventId }
						.sortedWith(referenceComparator),
				)
			}
			val selected = state.values.maxWithOrNull(transitionStateComparator) ?: continue
			bands += RawActivityBand(
				start = start,
				end = end,
				activity = selected.activity,
				mechanism = ActivityBandMechanism.TRANSITION,
				evidence = (listOf(selected.reference) + application.changedReferences)
					.distinctBy { it.sourceEventId }
					.sortedWith(referenceComparator),
			)
		}
		return TransitionComposition(bands, unchangedCount)
	}

	private fun applyTransitions(
		state: MutableMap<CapturedActivityType, ActivityCapturedObservation.Transition>,
		atSameTime: List<ActivityCapturedObservation.Transition>,
	): TransitionApplication {
		var unchanged = 0
		val changed = mutableListOf<ActivityCapturedObservationReference>()
		atSameTime.sortedWith(transitionApplicationComparator).forEach { transition ->
			when (transition.change) {
				ActivityTransitionChange.EXIT -> {
					if (state.remove(transition.activity) == null) {
						unchanged += 1
					} else {
						changed += transition.reference
					}
				}
				ActivityTransitionChange.ENTER -> {
					if (transition.activity in state) {
						unchanged += 1
					} else {
						state[transition.activity] = transition
						changed += transition.reference
					}
				}
			}
		}
		return TransitionApplication(unchanged, changed)
	}

	private fun composeAllBands(
		request: ActivityCapturedCoalescingRequest,
		transitionBands: List<RawActivityBand>,
		sampled: List<ActivityCapturedObservation.SampledClassification>,
		gaps: List<ActivityCoverageGap>,
	): List<RawActivityBand> {
		val boundaries = buildSet {
			add(request.intervalStartElapsedRealtimeNanos)
			add(request.intervalEndExclusiveElapsedRealtimeNanos)
			transitionBands.forEach { band ->
				add(band.start)
				add(band.end)
			}
			sampled.forEach { observation ->
				add(
					observation.reference.providerElapsedRealtimeNanos.coerceAtLeast(
						request.intervalStartElapsedRealtimeNanos,
					),
				)
				add(
					observation.coverageEndExclusiveElapsedRealtimeNanos.coerceAtMost(
						request.intervalEndExclusiveElapsedRealtimeNanos,
					),
				)
			}
			gaps.forEach { gap ->
				add(gap.intervalStartElapsedRealtimeNanos)
				add(gap.intervalEndExclusiveElapsedRealtimeNanos)
			}
		}.filter {
			it in request.intervalStartElapsedRealtimeNanos..request.intervalEndExclusiveElapsedRealtimeNanos
		}.sorted()
		return buildList {
			for (index in 0 until boundaries.lastIndex) {
				val start = boundaries[index]
				val end = boundaries[index + 1]
				if (start == end || gaps.any { start in it }) continue
				val transitionBand = transitionBands.firstOrNull { start in it }
				if (transitionBand != null) {
					add(transitionBand.copy(start = start, end = end))
					continue
				}
				val selected = sampled.asSequence()
					.filter { observation ->
						observation.reference.providerElapsedRealtimeNanos <= start &&
							observation.coverageEndExclusiveElapsedRealtimeNanos >= end &&
							!gaps.interruptCoverage(
								observation.reference.providerElapsedRealtimeNanos,
								start,
							)
					}
					.maxWithOrNull(sampledSelectionComparator)
					?: continue
				add(
					RawActivityBand(
						start = start,
						end = end,
						activity = selected.activity,
						mechanism = ActivityBandMechanism.SAMPLED_CLASSIFICATION,
						evidence = listOf(selected.reference),
					),
				)
			}
		}
	}

	private fun List<RawActivityBand>.mergeAdjacent(): List<RawActivityBand> =
		fold(mutableListOf()) { merged, next ->
			val previous = merged.lastOrNull()
			if (previous != null && previous.end == next.start &&
				previous.activity == next.activity && previous.mechanism == next.mechanism
			) {
				merged[merged.lastIndex] = previous.copy(
					end = next.end,
					evidence = (previous.evidence + next.evidence)
						.distinctBy { it.sourceEventId }
						.sortedWith(referenceComparator),
				)
			} else {
				merged += next
			}
			merged
		}

	private fun RawActivityBand.toCapturedBand(
		mutation: ActivityCapturedWindowMutation,
		fragmentOrdinal: Int,
		observations: List<ActivityCapturedObservation>,
	): ActivityCapturedBand? {
		val confidence = when (mechanism) {
			ActivityBandMechanism.TRANSITION -> ActivityBandConfidence.TransitionSignal
			ActivityBandMechanism.SAMPLED_REFINEMENT,
			ActivityBandMechanism.SAMPLED_CLASSIFICATION -> {
				val confidenceByIdentity = observations.asSequence()
					.filterIsInstance<ActivityCapturedObservation.SampledClassification>()
					.associate { it.reference.sourceEventId to it.confidencePercent }
				val values = evidence.mapNotNull { confidenceByIdentity[it.sourceEventId] }
				if (values.isEmpty()) return null
				ActivityBandConfidence.Sampled(
					minimumPercent = values.minOrNull()!!,
					maximumPercent = values.maxOrNull()!!,
					observationCount = values.size,
				)
			}
		}
		val wallTimeRange = deriveWallTimeRange(this, observations) ?: return null
		return ActivityCapturedBand(
			key = ActivityCapturedFactKey(mutation, fragmentOrdinal),
			intervalStartElapsedRealtimeNanos = start,
			intervalEndExclusiveElapsedRealtimeNanos = end,
			wallTimeRange = wallTimeRange,
			activity = activity,
			mechanism = mechanism,
			confidence = confidence,
			evidence = evidence,
		)
	}

	private fun deriveWallTimeRange(
		band: RawActivityBand,
		observations: List<ActivityCapturedObservation>,
	): ActivityDerivedWallTimeRange? {
		val evidenceIds = band.evidence.mapTo(mutableSetOf()) { it.sourceEventId }
		val anchors = observations.filter { it.reference.sourceEventId in evidenceIds }
		if (anchors.isEmpty()) return null
		val start = deriveWallTimeBoundary(band.start, anchors) ?: return null
		val end = deriveWallTimeBoundary(band.end, anchors) ?: return null
		val continuity = when {
			start.authority.anchorSourceEventId == end.authority.anchorSourceEventId ->
				ActivityWallTimeContinuity.SAME_ANCHOR
			wallTimeMappingsAgree(band, start, end) ->
				ActivityWallTimeContinuity.CONSISTENT_WITHIN_UNCERTAINTY
			else -> ActivityWallTimeContinuity.DISCONTINUITY_DETECTED
		}
		return ActivityDerivedWallTimeRange(start, end, continuity)
	}

	private fun deriveWallTimeBoundary(
		providerTime: Long,
		anchors: List<ActivityCapturedObservation>,
	): ActivityDerivedWallTimeBoundary? {
		val anchor = anchors.minWithOrNull(
			compareBy<ActivityCapturedObservation> {
				unsignedDistance(it.reference.providerElapsedRealtimeNanos, providerTime)
			}.thenBy { it.reference.providerElapsedRealtimeNanos }
				.thenBy { it.reference.sourceSequence }
				.thenBy { it.reference.admissionOrdinal }
				.thenBy { it.reference.sourceEventId.value },
		) ?: return null
		val anchorProviderTime = anchor.reference.providerElapsedRealtimeNanos
		val deltaNanos = if (providerTime >= anchorProviderTime) {
			providerTime - anchorProviderTime
		} else {
			-(anchorProviderTime - providerTime)
		}
		val deltaMs = deltaNanos / NANOS_PER_MILLISECOND
		val wallTimeMs = checkedAdd(anchor.observedWallTimeMs, deltaMs) ?: return null
		if (wallTimeMs < 0L) return null
		val roundingUncertainty = if (deltaNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
		val uncertainty = checkedAdd(anchor.wallTimeUncertaintyMs, roundingUncertainty)
			?: return null
		return ActivityDerivedWallTimeBoundary(
			wallTimeMs = wallTimeMs,
			uncertaintyMs = uncertainty,
			authority = ActivityWallTimeDerivationAuthority(
				kind = if (providerTime == anchorProviderTime) {
					ActivityWallTimeBoundaryKind.EXACT_PROVIDER_OBSERVATION
				} else {
					ActivityWallTimeBoundaryKind.SAME_CLOCK_EXTRAPOLATION
				},
				anchorSourceEventId = anchor.reference.sourceEventId,
				anchorProviderElapsedRealtimeNanos = anchorProviderTime,
				clockDomainId = anchor.authority.clockDomainId,
			),
		)
	}

	private fun wallTimeMappingsAgree(
		band: RawActivityBand,
		start: ActivityDerivedWallTimeBoundary,
		end: ActivityDerivedWallTimeBoundary,
	): Boolean {
		val expectedDeltaMs = (band.end - band.start) / NANOS_PER_MILLISECOND
		val actualDeltaMs = end.wallTimeMs - start.wallTimeMs
		val allowedDifference = saturatedAdd(start.uncertaintyMs, end.uncertaintyMs)
		val minimum = saturatedSubtract(expectedDeltaMs, allowedDifference)
		val maximum = saturatedAdd(expectedDeltaMs, allowedDifference)
		return actualDeltaMs in minimum..maximum
	}

	private fun complementWithDeclaredGaps(
		request: ActivityCapturedCoalescingRequest,
		bands: List<ActivityCapturedBand>,
		declaredGaps: List<ActivityCoverageGap>,
	): List<ActivityCoverageGap> {
		val boundaries = buildSet {
			add(request.intervalStartElapsedRealtimeNanos)
			add(request.intervalEndExclusiveElapsedRealtimeNanos)
			bands.forEach { band ->
				add(band.intervalStartElapsedRealtimeNanos)
				add(band.intervalEndExclusiveElapsedRealtimeNanos)
			}
			declaredGaps.forEach { gap ->
				add(gap.intervalStartElapsedRealtimeNanos)
				add(gap.intervalEndExclusiveElapsedRealtimeNanos)
			}
		}.sorted()
		return buildList {
			for (index in 0 until boundaries.lastIndex) {
				val start = boundaries[index]
				val end = boundaries[index + 1]
				if (bands.any { start in it }) continue
				val reason = declaredGaps.firstOrNull { start in it }?.reason
					?: ActivityCoverageGapReason.NO_QUALIFIED_EVIDENCE
				val previous = lastOrNull()
				if (previous != null && previous.intervalEndExclusiveElapsedRealtimeNanos == start &&
					previous.reason == reason
				) {
					this[lastIndex] = previous.copy(
						intervalEndExclusiveElapsedRealtimeNanos = end,
					)
				} else {
					add(ActivityCoverageGap(start, end, reason))
				}
			}
		}
	}

	private fun List<ActivityCoverageGap>.areValidFor(
		request: ActivityCapturedCoalescingRequest,
	): Boolean = all { gap ->
		gap.intervalStartElapsedRealtimeNanos >= request.intervalStartElapsedRealtimeNanos &&
			gap.intervalEndExclusiveElapsedRealtimeNanos <=
				request.intervalEndExclusiveElapsedRealtimeNanos
	} && zipWithNext().all { (left, right) ->
		left.intervalEndExclusiveElapsedRealtimeNanos <=
			right.intervalStartElapsedRealtimeNanos
	}

	private fun List<ActivityCoverageGap>.interruptCoverage(
		observationTime: Long,
		intervalStart: Long,
	): Boolean = any { gap ->
		gap.intervalStartElapsedRealtimeNanos >= observationTime &&
			gap.intervalEndExclusiveElapsedRealtimeNanos <= intervalStart
	}

	private operator fun ActivityCoverageGap.contains(time: Long): Boolean =
		time >= intervalStartElapsedRealtimeNanos && time < intervalEndExclusiveElapsedRealtimeNanos

	private operator fun RawActivityBand.contains(time: Long): Boolean =
		time >= start && time < end

	private operator fun ActivityCapturedBand.contains(time: Long): Boolean =
		time >= intervalStartElapsedRealtimeNanos &&
			time < intervalEndExclusiveElapsedRealtimeNanos

	private fun ActivityCapturedObservation.semanticKey() = ActivitySemanticKey(
		providerTime = reference.providerElapsedRealtimeNanos,
		activity = activity,
		transitionChange = (this as? ActivityCapturedObservation.Transition)?.change,
		sampledConfidence =
			(this as? ActivityCapturedObservation.SampledClassification)?.confidencePercent,
		sampledCoverageEnd =
			(this as? ActivityCapturedObservation.SampledClassification)
				?.coverageEndExclusiveElapsedRealtimeNanos,
	)

	private fun rejected(reason: ActivityCoalescingRejection) =
		ActivityCoalescingResult.Rejected(reason)

	private sealed interface Canonicalization {
		data class Success(
			val observations: List<ActivityCapturedObservation>,
			val exactDuplicateCount: Int,
			val semanticDuplicateCount: Int,
		) : Canonicalization

		data object IdentityCollision : Canonicalization
	}

	private data class ActivitySemanticKey(
		val providerTime: Long,
		val activity: CapturedActivityType,
		val transitionChange: ActivityTransitionChange?,
		val sampledConfidence: Int?,
		val sampledCoverageEnd: Long?,
	)

	private data class TransitionComposition(
		val bands: List<RawActivityBand>,
		val unchangedCount: Int,
	)

	private data class TransitionApplication(
		val unchangedCount: Int,
		val changedReferences: List<ActivityCapturedObservationReference>,
	) {
		companion object {
			val EMPTY = TransitionApplication(0, emptyList())
		}
	}

	private data class RawActivityBand(
		val start: Long,
		val end: Long,
		val activity: CapturedActivityType,
		val mechanism: ActivityBandMechanism,
		val evidence: List<ActivityCapturedObservationReference>,
	)

	private val referenceComparator =
		compareBy<ActivityCapturedObservationReference>(
			ActivityCapturedObservationReference::providerElapsedRealtimeNanos,
			ActivityCapturedObservationReference::sourceSequence,
			ActivityCapturedObservationReference::admissionOrdinal,
		).thenBy { it.sourceEventId.value }

	private val observationComparator =
		compareBy<ActivityCapturedObservation> {
			it.reference.providerElapsedRealtimeNanos
		}.thenBy {
			when ((it as? ActivityCapturedObservation.Transition)?.change) {
				ActivityTransitionChange.EXIT -> 0
				ActivityTransitionChange.ENTER -> 1
				null -> 2
			}
		}.thenBy { it.activity.ordinal }
			.thenBy { it.reference.sourceSequence }
			.thenBy { it.reference.admissionOrdinal }
			.thenBy { it.reference.sourceEventId.value }

	private val transitionApplicationComparator =
		compareBy<ActivityCapturedObservation.Transition> {
			if (it.change == ActivityTransitionChange.EXIT) 0 else 1
		}.thenBy { it.activity.ordinal }
			.thenBy { it.reference.sourceSequence }
			.thenBy { it.reference.admissionOrdinal }
			.thenBy { it.reference.sourceEventId.value }

	private val transitionStateComparator =
		compareBy<ActivityCapturedObservation.Transition> {
			it.reference.providerElapsedRealtimeNanos
		}.thenBy { activityPriority(it.activity) }
			.thenBy { it.reference.sourceSequence }
			.thenBy { it.reference.admissionOrdinal }
			.thenBy { it.reference.sourceEventId.value }

	private val sampledSelectionComparator =
		compareBy<ActivityCapturedObservation.SampledClassification> {
			it.confidencePercent
		}.thenBy { it.reference.providerElapsedRealtimeNanos }
			.thenBy { activityPriority(it.activity) }
			.thenBy { it.reference.sourceSequence }
			.thenBy { it.reference.admissionOrdinal }
			.thenBy { it.reference.sourceEventId.value }

	private fun activityPriority(activity: CapturedActivityType): Int = when (activity) {
		CapturedActivityType.RUNNING -> 8
		CapturedActivityType.WALKING -> 7
		CapturedActivityType.ON_BICYCLE -> 6
		CapturedActivityType.IN_VEHICLE -> 5
		CapturedActivityType.ON_FOOT -> 4
		CapturedActivityType.STILL -> 3
		CapturedActivityType.TILTING -> 2
		CapturedActivityType.UNKNOWN -> 1
	}

	private fun unsignedDistance(left: Long, right: Long): Long =
		if (left >= right) left - right else right - left

	private fun checkedAdd(left: Long, right: Long): Long? = when {
		right > 0L && left > Long.MAX_VALUE - right -> null
		right < 0L && left < Long.MIN_VALUE - right -> null
		else -> left + right
	}

	private fun saturatedAdd(left: Long, right: Long): Long =
		checkedAdd(left, right) ?: Long.MAX_VALUE

	private fun saturatedSubtract(left: Long, right: Long): Long =
		if (left < Long.MIN_VALUE + right) Long.MIN_VALUE else left - right

	private const val MAX_OBSERVATIONS = 4_096
	private const val MAX_DECLARED_GAPS = 512
	private const val NANOS_PER_MILLISECOND = 1_000_000L
}
