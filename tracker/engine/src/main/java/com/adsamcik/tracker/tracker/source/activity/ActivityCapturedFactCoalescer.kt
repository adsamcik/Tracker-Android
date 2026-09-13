package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.tracker.source.model.SourceEventId
import java.util.EnumMap
import java.util.PriorityQueue
import java.util.TreeSet

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
	WINDOW_OUTSIDE_CAPTURE_VALIDITY,
	OBSERVATION_OUTSIDE_CAPTURE_VALIDITY,
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
 * Pure, bounded Activity fact composition. Exact transitions define the state envelope; only
 * compatible direct-capture samples can refine a coarse transition or fill uncovered time.
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
		val captureValidity = request.authority.temporalAuthority.capturedIntersection
		val requestedWindow = ActivityProviderTimeInterval(
			request.intervalStartElapsedRealtimeNanos,
			request.intervalEndExclusiveElapsedRealtimeNanos,
		)
		if (!captureValidity.contains(requestedWindow)) {
			return rejected(ActivityCoalescingRejection.WINDOW_OUTSIDE_CAPTURE_VALIDITY)
		}
		if (request.observations.any {
				it.reference.providerElapsedRealtimeNanos !in captureValidity
			}
		) return rejected(ActivityCoalescingRejection.OBSERVATION_OUTSIDE_CAPTURE_VALIDITY)
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
		if (request.observations.intersects(gaps)) {
			return rejected(ActivityCoalescingRejection.OBSERVATION_INSIDE_DECLARED_GAP)
		}

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
		val bandComposition = composeAllBands(
			request = request,
			transitionBands = transitionComposition.bands,
			sampled = sampled,
			gaps = gaps,
			negativeBoundaries = transitionComposition.negativeBoundaries,
		)
		val rawBands = bandComposition.bands
		val observationsById = canonical.observations.associateBy {
			it.reference.sourceEventId
		}
		val bands = rawBands.mergeAdjacent().mapIndexed { fragmentOrdinal, raw ->
			raw.toCapturedBand(request.mutation, fragmentOrdinal, observationsById)
		}
		if (bands.any { it == null }) {
			return rejected(ActivityCoalescingRejection.WALL_TIME_DERIVATION_FAILED)
		}
		val capturedBands = bands.filterNotNull()
		val coveredGaps = complementWithDeclaredGaps(request, capturedBands, gaps)
		val usedEvidenceIds = rawBands.asSequence()
			.flatMap { it.evidence.asSequence() }
			.map(ActivityCapturedObservationReference::sourceEventId)
			.toSet()
		val unchangedSamples = sampled.count { it.reference.sourceEventId !in usedEvidenceIds }
		val effectiveNegativeIds = usedEvidenceIds +
			bandComposition.effectiveNegativeBoundaryIds
		val unchangedUnmatchedExits = transitionComposition.unmatchedExitIds.count {
			it !in effectiveNegativeIds
		}

		return ActivityCoalescingResult.Coalesced(
			ActivityCapturedWindow(
				mutation = request.mutation,
				bands = capturedBands,
				gaps = coveredGaps,
				exactDuplicateCount = canonical.exactDuplicateCount,
				semanticDuplicateCount = canonical.semanticDuplicateCount,
				unchangedEvidenceCount = transitionComposition.redundantEnterCount +
					unchangedSamples + unchangedUnmatchedExits,
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
		var redundantEnterCount = 0
		val negativeBoundaries = mutableListOf<NegativeActivityBoundary>()
		val unmatchedExitIds = mutableSetOf<SourceEventId>()
		transitionsByTime.keys.asSequence()
			.filter { it < request.intervalStartElapsedRealtimeNanos }
			.sorted()
			.forEach { time ->
				val application = applyTransitions(
					state,
					transitionsByTime.getValue(time),
				)
				redundantEnterCount += application.redundantEnterCount
				negativeBoundaries += application.negativeBoundaries
				unmatchedExitIds += application.unmatchedExitIds
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
		var gapIndex = 0
		for (index in 0 until boundaries.lastIndex) {
			val start = boundaries[index]
			val end = boundaries[index + 1]
			while (gapIndex < gaps.size &&
				gaps[gapIndex].intervalEndExclusiveElapsedRealtimeNanos <= start
			) gapIndex += 1
			val containingGap = gaps.getOrNull(gapIndex)?.takeIf { start in it }
			if (containingGap?.intervalStartElapsedRealtimeNanos == start) state.clear()
			if (containingGap != null) continue
			val application = transitionsByTime[start]?.let { atStart ->
				applyTransitions(state, atStart)
			} ?: TransitionApplication.EMPTY
			redundantEnterCount += application.redundantEnterCount
			negativeBoundaries += application.negativeBoundaries
			unmatchedExitIds += application.unmatchedExitIds
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
		return TransitionComposition(
			bands = bands,
			redundantEnterCount = redundantEnterCount,
			negativeBoundaries = negativeBoundaries.sortedWith(negativeBoundaryComparator),
			unmatchedExitIds = unmatchedExitIds,
		)
	}

	private fun applyTransitions(
		state: MutableMap<CapturedActivityType, ActivityCapturedObservation.Transition>,
		atSameTime: List<ActivityCapturedObservation.Transition>,
	): TransitionApplication {
		var redundantEnters = 0
		val changed = mutableListOf<ActivityCapturedObservationReference>()
		val negativeBoundaries = mutableListOf<NegativeActivityBoundary>()
		val unmatchedExitIds = mutableSetOf<SourceEventId>()
		atSameTime.sortedWith(transitionApplicationComparator).forEach { transition ->
			when (transition.change) {
				ActivityTransitionChange.EXIT -> {
					negativeBoundaries += NegativeActivityBoundary(
						providerTime = transition.reference.providerElapsedRealtimeNanos,
						activity = transition.activity,
						reference = transition.reference,
					)
					if (state.remove(transition.activity) == null) {
						unmatchedExitIds += transition.reference.sourceEventId
					} else {
						changed += transition.reference
					}
				}
				ActivityTransitionChange.ENTER -> {
					if (transition.activity in state) {
						redundantEnters += 1
					} else {
						state[transition.activity] = transition
						changed += transition.reference
					}
				}
			}
		}
		return TransitionApplication(
			redundantEnterCount = redundantEnters,
			changedReferences = changed,
			negativeBoundaries = negativeBoundaries,
			unmatchedExitIds = unmatchedExitIds,
		)
	}

	private fun composeAllBands(
		request: ActivityCapturedCoalescingRequest,
		transitionBands: List<RawActivityBand>,
		sampled: List<ActivityCapturedObservation.SampledClassification>,
		gaps: List<ActivityCoverageGap>,
		negativeBoundaries: List<NegativeActivityBoundary>,
	): AllBandComposition {
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
			negativeBoundaries.forEach { boundary ->
				if (boundary.providerTime >= request.intervalStartElapsedRealtimeNanos) {
					add(boundary.providerTime)
				}
			}
		}.filter {
			it in request.intervalStartElapsedRealtimeNanos..request.intervalEndExclusiveElapsedRealtimeNanos
		}.sorted()
		val samplesByStart = sampled.sortedWith(sampledStartComparator)
		val samples = ActiveSamples(sampledSelectionComparator)
		val negativeByTime = negativeBoundaries.groupBy(NegativeActivityBoundary::providerTime)
		val effectiveNegativeIds = mutableSetOf<SourceEventId>()
		val bands = mutableListOf<RawActivityBand>()
		var sampleIndex = 0
		var negativeIndex = 0
		var transitionIndex = 0
		var gapIndex = 0
		val windowStart = request.intervalStartElapsedRealtimeNanos
		while (true) {
			val nextSampleTime = samplesByStart.getOrNull(sampleIndex)
				?.reference?.providerElapsedRealtimeNanos
				?.takeIf { it < windowStart }
				?: Long.MAX_VALUE
			val nextNegativeTime = negativeBoundaries.getOrNull(negativeIndex)
				?.providerTime
				?.takeIf { it < windowStart }
				?: Long.MAX_VALUE
			val nextTime = minOf(nextSampleTime, nextNegativeTime)
			if (nextTime == Long.MAX_VALUE) break
			samples.expireAt(nextTime)
			while (sampleIndex < samplesByStart.size &&
				samplesByStart[sampleIndex].reference.providerElapsedRealtimeNanos == nextTime
			) {
				val observation = samplesByStart[sampleIndex]
				if (observation.coverageEndExclusiveElapsedRealtimeNanos > windowStart) {
					samples.add(observation)
				}
				sampleIndex += 1
			}
			while (negativeIndex < negativeBoundaries.size &&
				negativeBoundaries[negativeIndex].providerTime == nextTime
			) {
				val boundary = negativeBoundaries[negativeIndex]
				if (samples.apply(boundary)) {
					effectiveNegativeIds += boundary.reference.sourceEventId
				}
				negativeIndex += 1
			}
		}
		for (index in 0 until boundaries.lastIndex) {
			val start = boundaries[index]
			val end = boundaries[index + 1]
			while (gapIndex < gaps.size &&
				gaps[gapIndex].intervalEndExclusiveElapsedRealtimeNanos <= start
			) gapIndex += 1
			val containingGap = gaps.getOrNull(gapIndex)?.takeIf { start in it }
			if (containingGap?.intervalStartElapsedRealtimeNanos == start) samples.clear()
			while (sampleIndex < samplesByStart.size &&
				samplesByStart[sampleIndex].reference.providerElapsedRealtimeNanos <= start
			) {
				val observation = samplesByStart[sampleIndex]
				if (containingGap == null &&
					observation.coverageEndExclusiveElapsedRealtimeNanos > start
				) samples.add(observation)
				sampleIndex += 1
			}
			samples.expireAt(start)
			while (negativeIndex < negativeBoundaries.size &&
				negativeBoundaries[negativeIndex].providerTime <= start
			) {
				val boundary = negativeBoundaries[negativeIndex]
				if (samples.apply(boundary)) {
					effectiveNegativeIds += boundary.reference.sourceEventId
				}
				negativeIndex += 1
			}
			if (start == end || containingGap != null) continue
			while (transitionIndex < transitionBands.size &&
				transitionBands[transitionIndex].end <= start
			) transitionIndex += 1
			val transitionBand = transitionBands.getOrNull(transitionIndex)
				?.takeIf { start in it }
			val selected = if (transitionBand == null) {
				samples.best()
			} else {
				samples.best(compatibleRefinements(transitionBand.activity))
			}
			if (transitionBand != null && selected == null) {
				bands += transitionBand.copy(start = start, end = end)
				continue
			}
			if (selected == null) continue
			val closureEvidence = negativeByTime[end].orEmpty()
				.filter { boundary -> boundary.clips(selected) }
				.map(NegativeActivityBoundary::reference)
			val evidence = (transitionBand?.evidence.orEmpty() +
				selected.reference + closureEvidence)
				.distinctBy { it.sourceEventId }
				.sortedWith(referenceComparator)
			bands += RawActivityBand(
				start = start,
				end = end,
				activity = selected.activity,
				mechanism = if (transitionBand == null) {
					ActivityBandMechanism.SAMPLED_CLASSIFICATION
				} else {
					ActivityBandMechanism.SAMPLED_REFINEMENT
				},
				refinedTransitionActivity = transitionBand?.activity,
				evidence = evidence,
			)
		}
		return AllBandComposition(bands, effectiveNegativeIds)
	}

	private fun List<RawActivityBand>.mergeAdjacent(): List<RawActivityBand> =
		fold(mutableListOf()) { merged, next ->
			val previous = merged.lastOrNull()
			if (previous != null && previous.end == next.start &&
				previous.activity == next.activity && previous.mechanism == next.mechanism &&
				previous.refinedTransitionActivity == next.refinedTransitionActivity
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
		observationsById: Map<SourceEventId, ActivityCapturedObservation>,
	): ActivityCapturedBand? {
		val confidence = when (mechanism) {
			ActivityBandMechanism.TRANSITION -> ActivityBandConfidence.TransitionSignal
			ActivityBandMechanism.SAMPLED_REFINEMENT,
			ActivityBandMechanism.SAMPLED_CLASSIFICATION -> {
				val values = evidence.mapNotNull { reference ->
					(observationsById[reference.sourceEventId] as?
						ActivityCapturedObservation.SampledClassification)?.confidencePercent
				}
				if (values.isEmpty()) return null
				ActivityBandConfidence.Sampled(
					minimumPercent = values.minOrNull()!!,
					maximumPercent = values.maxOrNull()!!,
					observationCount = values.size,
				)
			}
		}
		val wallTimeRange = deriveWallTimeRange(this, observationsById) ?: return null
		return ActivityCapturedBand(
			key = ActivityCapturedFactKey(mutation, fragmentOrdinal),
			intervalStartElapsedRealtimeNanos = start,
			intervalEndExclusiveElapsedRealtimeNanos = end,
			wallTimeRange = wallTimeRange,
			activity = activity,
			mechanism = mechanism,
			refinedTransitionActivity = refinedTransitionActivity,
			confidence = confidence,
			evidence = evidence,
		)
	}

	private fun deriveWallTimeRange(
		band: RawActivityBand,
		observationsById: Map<SourceEventId, ActivityCapturedObservation>,
	): ActivityDerivedWallTimeRange? {
		val anchors = band.evidence.mapNotNull { observationsById[it.sourceEventId] }
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
			var bandIndex = 0
			var gapIndex = 0
			for (index in 0 until boundaries.lastIndex) {
				val start = boundaries[index]
				val end = boundaries[index + 1]
				while (bandIndex < bands.size &&
					bands[bandIndex].intervalEndExclusiveElapsedRealtimeNanos <= start
				) bandIndex += 1
				if (bands.getOrNull(bandIndex)?.let { start in it } == true) continue
				while (gapIndex < declaredGaps.size &&
					declaredGaps[gapIndex].intervalEndExclusiveElapsedRealtimeNanos <= start
				) gapIndex += 1
				val reason = declaredGaps.getOrNull(gapIndex)
					?.takeIf { start in it }
					?.reason
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

	private fun List<ActivityCapturedObservation>.intersects(
		gaps: List<ActivityCoverageGap>,
	): Boolean {
		val observations = sortedBy { it.reference.providerElapsedRealtimeNanos }
		var gapIndex = 0
		for (observation in observations) {
			val providerTime = observation.reference.providerElapsedRealtimeNanos
			while (gapIndex < gaps.size &&
				gaps[gapIndex].intervalEndExclusiveElapsedRealtimeNanos <= providerTime
			) gapIndex += 1
			if (gaps.getOrNull(gapIndex)?.let { providerTime in it } == true) return true
		}
		return false
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
		val redundantEnterCount: Int,
		val negativeBoundaries: List<NegativeActivityBoundary>,
		val unmatchedExitIds: Set<SourceEventId>,
	)

	private data class TransitionApplication(
		val redundantEnterCount: Int,
		val changedReferences: List<ActivityCapturedObservationReference>,
		val negativeBoundaries: List<NegativeActivityBoundary>,
		val unmatchedExitIds: Set<SourceEventId>,
	) {
		companion object {
			val EMPTY = TransitionApplication(0, emptyList(), emptyList(), emptySet())
		}
	}

	private data class NegativeActivityBoundary(
		val providerTime: Long,
		val activity: CapturedActivityType,
		val reference: ActivityCapturedObservationReference,
	) {
		fun negates(sampledActivity: CapturedActivityType): Boolean =
			isCompatibleActivityNegativeBoundary(activity, sampledActivity)

		fun clips(observation: ActivityCapturedObservation.SampledClassification): Boolean {
			if (!negates(observation.activity)) return false
			val sampleTime = observation.reference.providerElapsedRealtimeNanos
			return sampleTime < providerTime ||
				(sampleTime == providerTime &&
					observation.reference.sourceSequence <= reference.sourceSequence)
		}
	}

	private data class AllBandComposition(
		val bands: List<RawActivityBand>,
		val effectiveNegativeBoundaryIds: Set<SourceEventId>,
	)

	private data class RawActivityBand(
		val start: Long,
		val end: Long,
		val activity: CapturedActivityType,
		val mechanism: ActivityBandMechanism,
		val refinedTransitionActivity: CapturedActivityType? = null,
		val evidence: List<ActivityCapturedObservationReference>,
	)

	private class ActiveSamples(
		private val selectionComparator: Comparator<
			ActivityCapturedObservation.SampledClassification
		>,
	) {
		private val byActivity = EnumMap<
			CapturedActivityType,
			TreeSet<ActivityCapturedObservation.SampledClassification>
		>(CapturedActivityType::class.java).apply {
			CapturedActivityType.values().forEach { activity ->
				put(activity, TreeSet(selectionComparator))
			}
		}
		private val expirations = PriorityQueue(
			compareBy<ActivityCapturedObservation.SampledClassification> {
				it.coverageEndExclusiveElapsedRealtimeNanos
			}.thenBy { it.reference.sourceEventId.value },
		)

		fun add(observation: ActivityCapturedObservation.SampledClassification) {
			byActivity.getValue(observation.activity).add(observation)
			expirations.add(observation)
		}

		fun expireAt(providerTime: Long) {
			while (expirations.peek()?.coverageEndExclusiveElapsedRealtimeNanos
				?.let { it <= providerTime } == true
			) {
				val expired = expirations.remove()
				byActivity.getValue(expired.activity).remove(expired)
			}
		}

		fun clear() {
			byActivity.values.forEach { it.clear() }
			expirations.clear()
		}

		fun apply(boundary: NegativeActivityBoundary): Boolean {
			var removed = false
			CapturedActivityType.values().asSequence()
				.filter(boundary::negates)
				.forEach { activity ->
					val iterator = byActivity.getValue(activity).iterator()
					while (iterator.hasNext()) {
						val observation = iterator.next()
						if (boundary.clips(observation)) {
							iterator.remove()
							removed = true
						}
					}
				}
			return removed
		}

		fun best(
			allowedActivities: Set<CapturedActivityType>? = null,
		): ActivityCapturedObservation.SampledClassification? =
			(allowedActivities ?: ActivityCapturedFactCoalescer.ALL_ACTIVITY_TYPES)
				.asSequence()
				.mapNotNull { activity ->
					byActivity.getValue(activity).let { active ->
						if (active.isEmpty()) null else active.last()
					}
				}
				.maxWithOrNull(selectionComparator)
	}

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

	private val negativeBoundaryComparator =
		compareBy<NegativeActivityBoundary>(NegativeActivityBoundary::providerTime)
			.thenBy { it.activity.ordinal }
			.thenBy { it.reference.sourceSequence }
			.thenBy { it.reference.admissionOrdinal }
			.thenBy { it.reference.sourceEventId.value }

	private val sampledStartComparator =
		compareBy<ActivityCapturedObservation.SampledClassification> {
			it.reference.providerElapsedRealtimeNanos
		}.thenBy { it.reference.sourceSequence }
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

	private fun compatibleRefinements(
		transitionActivity: CapturedActivityType,
	): Set<CapturedActivityType> = COMPATIBLE_REFINEMENTS.getValue(transitionActivity)

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

	private val ALL_ACTIVITY_TYPES = CapturedActivityType.values().toSet()
	private val COMPATIBLE_REFINEMENTS = CapturedActivityType.values().associateWith { coarse ->
		CapturedActivityType.values().filterTo(mutableSetOf()) { detail ->
			isCompatibleActivityRefinement(coarse, detail)
		}
	}

	private const val MAX_OBSERVATIONS = 4_096
	private const val MAX_DECLARED_GAPS = 512
	private const val NANOS_PER_MILLISECOND = 1_000_000L
}
