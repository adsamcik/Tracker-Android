package com.adsamcik.tracker.tracker.source.projection

import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourceQualityFlag

data class JoinSpec(
	val id: String,
	val primarySource: SourceKind,
	val input: Map<SourceKind, JoinInputSpec>,
	val allowedLatenessMs: Long,
	val missingInputTimeoutMs: Long,
	val lateCorrectionPolicy: LateCorrectionPolicy,
) {
	init {
		require(id.isNotBlank())
		require(primarySource in input)
		require(allowedLatenessMs >= 0L)
		require(missingInputTimeoutMs >= 0L)
	}
}

data class JoinInputSpec(
	val maximumAgeMs: Long,
	val direction: JoinDirection,
	val requireSameClockDomain: Boolean = true,
	val requireSameLogicalTracking: Boolean = true,
	val requireWindowOverlap: Boolean = false,
	val required: Boolean = true,
	val minimumConfidence: Float? = null,
	val rejectedQualityFlags: Set<SourceQualityFlag> = emptySet(),
) {
	init {
		require(maximumAgeMs >= 0L)
		require(minimumConfidence == null || minimumConfidence in 0f..1f)
	}
}

enum class JoinDirection { BEFORE_OR_EQUAL, AFTER_OR_EQUAL, NEAREST, BRACKET, WINDOW_OVERLAP }
enum class LateCorrectionPolicy { VERSIONED_RECOMPUTATION, APPEND_ONLY_CORRECTION, QUARANTINE }

data class SourceWatermark(
	val source: SourceKind,
	val sourceInstanceId: String,
	val observedThroughElapsedRealtimeNanos: Long,
	val admittedThroughOrdinal: Long,
	val appDrainComplete: Boolean,
)

data class JoinedFrame(
	val frameId: String,
	val joinSpecId: String,
	val primaryEventId: String,
	val observationElapsedRealtimeNanos: Long,
	val clockDomainId: String,
	val inputs: Map<SourceKind, JoinedInput>,
	val finalization: JoinFinalization,
	val revision: Int = 1,
	val supersedesFrameId: String? = null,
	val emittedAtAdmissionOrdinal: Long = 0L,
)

data class JoinedInput(
	val eventIds: List<String>,
	val ageMs: Long?,
	val result: JoinInputResult,
)

enum class JoinInputResult { MATCHED, MISSING, STALE, CLOCK_DOMAIN_MISMATCH, FUTURE_REJECTED, LOW_QUALITY }
enum class JoinFinalization { PROVISIONAL, FINAL_COMPLETE, FINAL_MISSING_INPUT, CORRECTION }
