package com.adsamcik.tracker.stats.api.repository

import com.adsamcik.tracker.stats.api.value.EpochMs

/** Result of resolving Pressure history for one exact local session-segment row. */
sealed interface PressureSessionHistoryQuery {
	data object NotFound : PressureSessionHistoryQuery

	data class Found(
		val history: PressureSessionHistory,
	) : PressureSessionHistoryQuery
}

/** Pressure history attached to one exact physical session segment. */
data class PressureSessionHistory(
	val segmentId: Long,
	val capture: HistoryCapture,
	val qualifiedSources: Set<HistorySource>,
	val pressure: PressureHistory,
) {
	init {
		require(segmentId > 0L) { "Pressure session history requires a persisted segment identity" }
		val capturedSources = (capture as? HistoryCapture.Exact)?.revisions
			?.flatMapTo(linkedSetOf()) { it.capturedSources }
			.orEmpty()
		require(qualifiedSources.all(capturedSources::contains)) {
			"Qualified Pressure history requires exact historical capture authority"
		}
		require(!pressure.hasRetainedObservation || HistorySource.PRESSURE in capturedSources) {
			"Retained Pressure history requires exact historical Pressure capture authority"
		}
		require(
			(HistorySource.PRESSURE in qualifiedSources) == pressure.hasQualifiedRetainedProof,
		) {
			"Pressure qualification must match exact retained non-failed Pressure proof"
		}
	}
}

/** One opaque logical Pressure-only row. It grants no physical mutation or export authority. */
data class PressureOnlyHistoryEntry(
	val key: TrackingHistoryEntryKey,
	val origin: PressureHistoryOrigin,
	val startTime: EpochMs,
	val endTime: EpochMs,
	val pressure: PressureHistory,
) {
	init {
		require(endTime >= startTime) { "Pressure history entry cannot end before it starts" }
	}

	val state: PressureHistoryPresentationState
		get() = pressure.presentationState
}

/** Proven origin for a Pressure-only list row; neither form grants physical mutation authority. */
sealed interface PressureHistoryOrigin {
	/** A checksum-qualified local logical tracking entry. */
	data object Local : PressureHistoryOrigin

	/** A portable-origin entry identified only by its stable source-supplied opaque digest. */
	data class Imported(
		val identity: ImportedPressureHistoryIdentity,
	) : PressureHistoryOrigin
}

/** Stable opaque equality identity for an imported Pressure entry. */
@JvmInline
value class ImportedPressureHistoryIdentity(val value: String) {
	init {
		require(OPAQUE_PRESSURE_IDENTITY.matches(value)) {
			"Imported Pressure history identity must be an opaque SHA-256 digest"
		}
	}

	override fun toString(): String = "ImportedPressureHistoryIdentity"
}

private val OPAQUE_PRESSURE_IDENTITY = Regex("sha256:[0-9a-f]{64}")

/** Compact product state used by Pressure list and detail presentation. */
enum class PressureHistoryPresentationState {
	MATERIALIZING,
	PARTIAL,
	READY,
	DELETED,
	UNVERIFIABLE,
	UNAVAILABLE,
	FAILED,
}

/** How much of the requested session interval retained Pressure windows cover. */
enum class PressureHistoryCoverage {
	NONE,
	COMPLETE,
	PARTIAL,
	UNKNOWN,
}

/** Stable source-local causes for unavailable, incomplete, or failed Pressure history. */
enum class PressureHistoryCause {
	SOURCE_NOT_CAPTURED,
	LEGACY_UNATTRIBUTED,
	SEGMENT_MEMBERSHIP_INCOMPLETE,
	LOGICAL_MANIFEST_REVISION_UNION_INVALID,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_MEMBERSHIP_MISMATCH,
	SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
	SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	SOURCE_POLICY_ATTRIBUTION_INVALID,
	MIXED_WRITER_WITHIN_SERVICE_RUN,
	UNKNOWN_WRITER,
	PRODUCT_LANE_MISSING,
	PRODUCT_LANE_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	OUTSIDE_RETAINED_FLOOR,
	RETENTION_CROSSES_SEGMENT,
	RETENTION_TRUNCATED,
	RETENTION_TRUNCATION_MARKER_INVALID,
	COMPLETENESS_MISSING,
	COMPLETENESS_INVALID,
	APP_DRAIN_INCOMPLETE,
	STOP_INCOMPLETE,
	UNRESOLVED_PROVIDER_SEQUENCE,
	PROVIDER_COMPLETENESS_UNOBSERVABLE,
	PROVIDER_UNAVAILABLE,
	UNAVAILABLE_SENTINEL_WITH_RETAINED_FACTS,
	CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN,
	SERVICE_RUN_ACTIVE,
	FACTS_MISSING_FOR_ADMITTED_RUN,
	PRESSURE_FACT_INTEGRITY_FAILED,
	PRESSURE_FACT_CORRECTION_INCOMPLETE,
	STALE_COLLECTED_DATA_EPOCH,
	PARTIAL_FACT,
	TARGET_BEFORE_LANE_ACTIVATION,
	TERMINAL_PROJECTION_FAILURE,
	PRODUCT_LANE_CUTOFF_BEFORE_TARGET,
	PRODUCT_LANE_RETIRED_BEFORE_TARGET,
	PRODUCT_LANE_BEHIND,
	DELETED_FACTS,
	BATCH_DEPENDENCY_OVERFLOW,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	IMPORTED_SOURCE_EVIDENCE_STATE_MISSING,
	IMPORTED_STALE_COLLECTED_DATA_EPOCH,
	IMPORTED_DEPENDENCY_OVERFLOW,
}

enum class PressureSensorAccuracy { UNKNOWN, UNRELIABLE, LOW, MEDIUM, HIGH }
enum class PressureWindowClosure { TARGET_ELAPSED, SOURCE_BOUNDARY }
enum class PressureWindowQualification { COMPLETE, PARTIAL }

/**
 * One retained authenticated Pressure window.
 *
 * These are direct Pressure statistics. They are not elevation, ascent, or a calibrated vertical
 * estimate. Stored zone and sampling/gap fields remain attached to the window that owns them.
 */
@Suppress("LongParameterList")
data class PressureHistoryWindow(
	val intervalStartTime: EpochMs,
	val intervalEndTime: EpochMs,
	val sampleCount: Int,
	val expectedSampleCount: Int,
	val meanHectopascals: Double,
	val sumSquaredDeviations: Double,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val firstHectopascals: Float,
	val latestHectopascals: Float,
	val slopeHectopascalsPerSecond: Double?,
	val rSquared: Double?,
	val sensorAccuracy: PressureSensorAccuracy,
	val effectiveSamplePeriodMicros: Int,
	val effectiveMaximumReportLatencyMicros: Int,
	val targetWindowDurationNanos: Long,
	val maximumInterSampleGapNanos: Long,
	val closure: PressureWindowClosure,
	val qualification: PressureWindowQualification,
	val sourceQualityFlags: Long,
	val sourceQualityConfidence: Float?,
	val zoneId: String,
) {
	init {
		require(intervalEndTime >= intervalStartTime) { "Pressure window cannot end before it starts" }
		require(sampleCount > 0) { "Pressure window requires an observation" }
		require(expectedSampleCount > 0) { "Pressure window requires a positive expected count" }
		require(meanHectopascals.isFinite() && meanHectopascals > 0.0)
		require(sumSquaredDeviations.isFinite() && sumSquaredDeviations >= 0.0)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(firstHectopascals in minimumHectopascals..maximumHectopascals)
		require(latestHectopascals in minimumHectopascals..maximumHectopascals)
		require(slopeHectopascalsPerSecond == null || slopeHectopascalsPerSecond.isFinite())
		require(rSquared == null || rSquared.isFinite() && rSquared in 0.0..1.0)
		require(effectiveSamplePeriodMicros > 0)
		require(effectiveMaximumReportLatencyMicros >= 0)
		require(targetWindowDurationNanos > 0L)
		require(maximumInterSampleGapNanos >= 0L)
		require(sourceQualityFlags >= 0L)
		require(sourceQualityConfidence == null || sourceQualityConfidence in 0f..1f)
		require(zoneId.isNotBlank()) { "Pressure window requires stored zone authority" }
		val isComplete = closure == PressureWindowClosure.TARGET_ELAPSED &&
			sampleCount >= expectedSampleCount
		require((qualification == PressureWindowQualification.COMPLETE) == isComplete) {
			"Pressure window qualification must match retained closure and sample coverage"
		}
	}

	val actualToExpectedSampleRatio: Double
		get() = sampleCount.toDouble() / expectedSampleCount.toDouble()

	val sampleVarianceHectopascalsSquared: Double?
		get() = if (sampleCount > 1) sumSquaredDeviations / (sampleCount - 1).toDouble() else null
}

/** A contained summary that exists only when at least one real Pressure window exists. */
data class PressureHistorySummary(
	val firstHectopascals: Float,
	val latestHectopascals: Float,
	val minimumHectopascals: Float,
	val maximumHectopascals: Float,
	val windowCount: Int,
) {
	init {
		require(firstHectopascals.isFinite() && firstHectopascals > 0f)
		require(latestHectopascals.isFinite() && latestHectopascals > 0f)
		require(minimumHectopascals.isFinite() && minimumHectopascals > 0f)
		require(maximumHectopascals.isFinite() && maximumHectopascals >= minimumHectopascals)
		require(firstHectopascals in minimumHectopascals..maximumHectopascals)
		require(latestHectopascals in minimumHectopascals..maximumHectopascals)
		require(windowCount > 0)
	}
}

/** Retained Pressure facts plus the independent state needed to qualify them truthfully. */
data class PressureHistory(
	val availability: HistoryAvailability,
	val evidence: HistoryEvidence,
	val productState: HistoryProductState,
	val coverage: PressureHistoryCoverage,
	val windows: List<PressureHistoryWindow>,
	val causes: Set<PressureHistoryCause> = emptySet(),
) {
	init {
		if (windows.isEmpty()) {
			require(evidence != HistoryEvidence.RECORDED) {
				"Recorded Pressure evidence requires a retained window"
			}
			require(coverage != PressureHistoryCoverage.COMPLETE) {
				"Complete Pressure coverage requires a retained window"
			}
		} else {
			require(availability == HistoryAvailability.AVAILABLE) {
				"Retained Pressure windows require available historical evidence"
			}
			require(evidence == HistoryEvidence.RECORDED) {
				"Retained Pressure windows require recorded evidence"
			}
		}
		if (productState == HistoryProductState.READY) {
			require(windows.isNotEmpty() && coverage == PressureHistoryCoverage.COMPLETE) {
				"Ready Pressure history requires complete retained windows"
			}
		} else {
			require(causes.isNotEmpty()) {
				"Incomplete Pressure product state requires a named cause"
			}
		}
	}

	val hasRetainedObservation: Boolean
		get() = windows.isNotEmpty()

	/** Retained direct Pressure proof is qualified only while its product remains non-failed. */
	val hasQualifiedRetainedProof: Boolean
		get() = availability == HistoryAvailability.AVAILABLE &&
			evidence == HistoryEvidence.RECORDED &&
			windows.isNotEmpty() &&
			productState != HistoryProductState.FAILED

	val zoneAuthorities: Set<String>
		get() = windows.mapTo(linkedSetOf(), PressureHistoryWindow::zoneId)

	val summary: PressureHistorySummary?
		get() = if (windows.isEmpty()) {
			null
		} else {
			PressureHistorySummary(
				firstHectopascals = windows.first().firstHectopascals,
				latestHectopascals = windows.last().latestHectopascals,
				minimumHectopascals = windows.minOf(PressureHistoryWindow::minimumHectopascals),
				maximumHectopascals = windows.maxOf(PressureHistoryWindow::maximumHectopascals),
				windowCount = windows.size,
			)
		}

	val presentationState: PressureHistoryPresentationState
		get() = when {
			windows.isEmpty() && PressureHistoryCause.DELETED_FACTS in causes ->
				PressureHistoryPresentationState.DELETED
			windows.isEmpty() && causes.any { it in importedUnverifiableCauses } ->
				PressureHistoryPresentationState.UNVERIFIABLE
			productState == HistoryProductState.FAILED -> PressureHistoryPresentationState.FAILED
			productState == HistoryProductState.MATERIALIZING ->
				PressureHistoryPresentationState.MATERIALIZING
			availability != HistoryAvailability.AVAILABLE ->
				PressureHistoryPresentationState.UNAVAILABLE
			productState == HistoryProductState.READY -> PressureHistoryPresentationState.READY
			else -> PressureHistoryPresentationState.PARTIAL
		}
}

private val importedUnverifiableCauses = setOf(
	PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
	PressureHistoryCause.IMPORTED_SOURCE_EVIDENCE_STATE_MISSING,
	PressureHistoryCause.IMPORTED_STALE_COLLECTED_DATA_EPOCH,
	PressureHistoryCause.IMPORTED_DEPENDENCY_OVERFLOW,
)
