package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQuality
import java.security.MessageDigest
import javax.inject.Inject

data class JoinCandidate(
	val eventId: String,
	val source: SourceKind,
	val observedElapsedRealtimeNanos: Long,
	val clockDomainId: String,
	val logicalTrackingId: String? = null,
	val quality: SourceQuality = SourceQuality(),
	val windowStartElapsedRealtimeNanos: Long = observedElapsedRealtimeNanos,
	val windowEndElapsedRealtimeNanos: Long = observedElapsedRealtimeNanos,
) {
	init {
		require(eventId.isNotBlank())
		require(clockDomainId.isNotBlank())
		require(windowStartElapsedRealtimeNanos <= windowEndElapsedRealtimeNanos)
	}
}

class EventTimeJoiner @Inject constructor() {
	fun join(
		primary: JoinCandidate,
		available: Collection<JoinCandidate>,
		spec: JoinSpec,
		finalizationOverride: JoinFinalization? = null,
		revision: Int = 1,
		supersedesFrameId: String? = null,
		emittedAtAdmissionOrdinal: Long = 0L,
	): JoinedFrame {
		require(primary.source == spec.primarySource)
		require(revision > 0)
		val joined = spec.input.mapValues { (source, inputSpec) ->
			if (source == primary.source) {
				JoinedInput(listOf(primary.eventId), 0L, JoinInputResult.MATCHED)
			} else {
				select(primary, available.filter { it.source == source }, inputSpec)
			}
		}
		val inferredFinalization = if (spec.input.any { (source, inputSpec) ->
			inputSpec.required && joined.getValue(source).result != JoinInputResult.MATCHED
		}) {
			JoinFinalization.FINAL_MISSING_INPUT
		} else {
			JoinFinalization.FINAL_COMPLETE
		}
		val finalization = finalizationOverride ?: inferredFinalization
		return JoinedFrame(
			frameId = stableFrameId(spec.id, primary.eventId, joined, finalization),
			joinSpecId = spec.id,
			primaryEventId = primary.eventId,
			observationElapsedRealtimeNanos = primary.observedElapsedRealtimeNanos,
			clockDomainId = primary.clockDomainId,
			inputs = joined,
			finalization = finalization,
			revision = revision,
			supersedesFrameId = supersedesFrameId,
			emittedAtAdmissionOrdinal = emittedAtAdmissionOrdinal,
		)
	}

	private fun select(
		primary: JoinCandidate,
		candidates: List<JoinCandidate>,
		spec: JoinInputSpec,
	): JoinedInput {
		if (candidates.isEmpty()) return JoinedInput(emptyList(), null, JoinInputResult.MISSING)
		val clockCompatible = if (spec.requireSameClockDomain) {
			candidates.filter { it.clockDomainId == primary.clockDomainId }
		} else {
			candidates
		}
		if (clockCompatible.isEmpty()) {
			return JoinedInput(emptyList(), null, JoinInputResult.CLOCK_DOMAIN_MISMATCH)
		}
		val trackingCompatible = if (spec.requireSameLogicalTracking) {
			clockCompatible.filter { it.logicalTrackingId == primary.logicalTrackingId }
		} else {
			clockCompatible
		}
		if (trackingCompatible.isEmpty()) {
			return JoinedInput(emptyList(), null, JoinInputResult.MISSING)
		}
		val qualityCompatible = trackingCompatible.filter { candidate ->
			(spec.minimumConfidence == null ||
				(candidate.quality.confidence ?: 0f) >= spec.minimumConfidence) &&
				candidate.quality.flags.none(spec.rejectedQualityFlags::contains)
		}
		if (qualityCompatible.isEmpty()) {
			return JoinedInput(emptyList(), null, JoinInputResult.LOW_QUALITY)
		}

		val selected = when (spec.direction) {
			JoinDirection.BEFORE_OR_EQUAL -> qualityCompatible
				.filter { it.observedElapsedRealtimeNanos <= primary.observedElapsedRealtimeNanos }
				.maxByOrNull(JoinCandidate::observedElapsedRealtimeNanos)
				?.let(::listOf)
			JoinDirection.AFTER_OR_EQUAL -> qualityCompatible
				.filter { it.observedElapsedRealtimeNanos >= primary.observedElapsedRealtimeNanos }
				.minByOrNull(JoinCandidate::observedElapsedRealtimeNanos)
				?.let(::listOf)
			JoinDirection.NEAREST -> qualityCompatible.minByOrNull { candidate ->
				absoluteDifference(candidate.observedElapsedRealtimeNanos, primary.observedElapsedRealtimeNanos)
			}?.let(::listOf)
			JoinDirection.BRACKET -> {
				val before = qualityCompatible
					.filter { it.observedElapsedRealtimeNanos <= primary.observedElapsedRealtimeNanos }
					.maxByOrNull(JoinCandidate::observedElapsedRealtimeNanos)
				val after = qualityCompatible
					.filter { it.observedElapsedRealtimeNanos >= primary.observedElapsedRealtimeNanos }
					.minByOrNull(JoinCandidate::observedElapsedRealtimeNanos)
				if (before != null && after != null) listOf(before, after).distinctBy(JoinCandidate::eventId) else null
			}
			JoinDirection.WINDOW_OVERLAP -> qualityCompatible
				.filter { candidate ->
					candidate.windowStartElapsedRealtimeNanos <= primary.windowEndElapsedRealtimeNanos &&
						candidate.windowEndElapsedRealtimeNanos >= primary.windowStartElapsedRealtimeNanos
				}
				.sortedBy(JoinCandidate::observedElapsedRealtimeNanos)
				.takeIf(List<JoinCandidate>::isNotEmpty)
		}
		if (selected == null) {
			val futureOnly = spec.direction == JoinDirection.BEFORE_OR_EQUAL && qualityCompatible.all {
				it.observedElapsedRealtimeNanos > primary.observedElapsedRealtimeNanos
			}
			return JoinedInput(
				emptyList(),
				null,
				if (futureOnly) JoinInputResult.FUTURE_REJECTED else JoinInputResult.MISSING,
			)
		}
		val maximumAgeMs = selected.maxOf { candidate ->
			absoluteDifference(candidate.observedElapsedRealtimeNanos, primary.observedElapsedRealtimeNanos) /
				NANOS_PER_MILLISECOND
		}
		return if (maximumAgeMs > spec.maximumAgeMs) {
			JoinedInput(selected.map(JoinCandidate::eventId), maximumAgeMs, JoinInputResult.STALE)
		} else {
			JoinedInput(selected.map(JoinCandidate::eventId), maximumAgeMs, JoinInputResult.MATCHED)
		}
	}

	private fun stableFrameId(
		specId: String,
		primaryEventId: String,
		inputs: Map<SourceKind, JoinedInput>,
		finalization: JoinFinalization,
	): String {
		val identity = buildString {
			append(specId).append('|').append(primaryEventId).append('|').append(finalization.name)
			inputs.toSortedMap(compareBy(SourceKind::stableCode)).forEach { (source, input) ->
				append('|').append(source.stableCode).append(':').append(input.result.name).append(':')
				append(input.eventIds.sorted().joinToString(","))
			}
		}
		return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	private fun absoluteDifference(first: Long, second: Long): Long =
		if (first >= second) first - second else second - first

	private companion object {
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
