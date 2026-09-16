package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage as ApiPressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureHistoryWindow as ApiPressureHistoryWindow
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureSessionHistory
import com.adsamcik.tracker.stats.api.repository.PressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs

internal fun PressurePhysicalHistory.toPublicPressureSessionHistory() = PressureSessionHistory(
	segmentId = segment.id,
	capture = captureAuthority.toPublicCapture(),
	qualifiedSources = qualifiedSources.mapTo(linkedSetOf(), TrackingSourceComponent::toPublicSource),
	pressure = toPublicPressureHistory(),
)

internal fun PressureLogicalHistoryEntry.toPublicPressureOnlyEntryOrNull(): PressureOnlyHistoryEntry? {
	if (!hasExactPressureOnlyIntent) return null
	val logicalIdentity = identity as? PressureHistoryEntryIdentity.Logical ?: return null
	return PressureOnlyHistoryEntry(
		key = TrackingHistoryEntryKey("pressure:logical:${logicalIdentity.logicalTrackingId}"),
		origin = PressureHistoryOrigin.Local,
		startTime = EpochMs(physicalMembers.minOf { it.segment.startTimeMs }),
		endTime = EpochMs(physicalMembers.maxOf { it.segment.endTimeMs }),
		pressure = physicalMembers.toPublicPressureHistory(),
	)
}

internal fun PressureLogicalHistoryEntry.toSharedPressureOnlyEntryOrNull(): PressureOnlyHistoryEntry? =
	takeIf {
		isSharedSourceOnlyEligible
	}?.toPublicPressureOnlyEntryOrNull()

internal fun PressurePhysicalHistory.toPublicPressureHistory() = PressureHistory(
	availability = availability.toPublicAvailability(),
	evidence = evidence.toPublicEvidence(reasons),
	productState = materialization.toPublicProductState(availability, coverage, windows),
	coverage = coverage.toPublicCoverage(),
	windows = windows.map(PressureHistoryWindow::toPublicWindow),
	causes = reasons.mapTo(linkedSetOf(), PressureHistoryReason::toPublicCause),
)

private fun List<PressurePhysicalHistory>.toPublicPressureHistory(): PressureHistory {
	val publicMembers = map(PressurePhysicalHistory::toPublicPressureHistory)
	val retainedWindows = publicMembers.flatMap(PressureHistory::windows)
	val availability = when {
		retainedWindows.isNotEmpty() -> HistoryAvailability.AVAILABLE
		publicMembers.map(PressureHistory::availability).distinct().size == 1 ->
			publicMembers.first().availability
		else -> HistoryAvailability.UNAVAILABLE
	}
	val evidence = when {
		retainedWindows.isNotEmpty() -> HistoryEvidence.RECORDED
		publicMembers.any { it.evidence == HistoryEvidence.STARTING } -> HistoryEvidence.STARTING
		else -> HistoryEvidence.NONE
	}
	val coverage = when {
		retainedWindows.isNotEmpty() && publicMembers.all {
			it.coverage == ApiPressureHistoryCoverage.COMPLETE
		} -> ApiPressureHistoryCoverage.COMPLETE
		retainedWindows.isNotEmpty() -> ApiPressureHistoryCoverage.PARTIAL
		publicMembers.any { it.coverage == ApiPressureHistoryCoverage.UNKNOWN } ->
			ApiPressureHistoryCoverage.UNKNOWN
		publicMembers.any { it.coverage == ApiPressureHistoryCoverage.PARTIAL } ->
			ApiPressureHistoryCoverage.PARTIAL
		else -> ApiPressureHistoryCoverage.NONE
	}
	val productState = when {
		publicMembers.any { it.productState == HistoryProductState.FAILED } ->
			HistoryProductState.FAILED
		publicMembers.any { it.productState == HistoryProductState.MATERIALIZING } ->
			HistoryProductState.MATERIALIZING
		publicMembers.all { it.productState == HistoryProductState.READY } &&
			coverage == ApiPressureHistoryCoverage.COMPLETE -> HistoryProductState.READY
		retainedWindows.isNotEmpty() -> HistoryProductState.PARTIAL
		else -> HistoryProductState.DEGRADED
	}
	return PressureHistory(
		availability = availability,
		evidence = evidence,
		productState = productState,
		coverage = coverage,
		windows = retainedWindows,
		causes = publicMembers.flatMapTo(linkedSetOf(), PressureHistory::causes),
	)
}

private fun PressureHistoryAvailability.toPublicAvailability(): HistoryAvailability = when (this) {
	PressureHistoryAvailability.DISABLED -> HistoryAvailability.DISABLED
	PressureHistoryAvailability.AVAILABLE -> HistoryAvailability.AVAILABLE
	PressureHistoryAvailability.DELETED,
	PressureHistoryAvailability.UNAVAILABLE -> HistoryAvailability.UNAVAILABLE
}

private fun PressureHistoryEvidence.toPublicEvidence(
	reasons: Set<PressureHistoryReason>,
): HistoryEvidence = when (this) {
	PressureHistoryEvidence.RECORDED -> HistoryEvidence.RECORDED
	PressureHistoryEvidence.NO_OBSERVATION -> if (
		PressureHistoryReason.SERVICE_RUN_ACTIVE in reasons
	) {
		HistoryEvidence.STARTING
	} else {
		HistoryEvidence.NONE
	}
}

private fun PressureHistoryMaterialization.toPublicProductState(
	availability: PressureHistoryAvailability,
	coverage: PressureHistoryCoverage,
	windows: List<PressureHistoryWindow>,
): HistoryProductState = when (this) {
	PressureHistoryMaterialization.MATERIALIZING -> HistoryProductState.MATERIALIZING
	PressureHistoryMaterialization.FAILED -> HistoryProductState.FAILED
	PressureHistoryMaterialization.NOT_APPLICABLE -> HistoryProductState.DEGRADED
	PressureHistoryMaterialization.READY -> if (
		availability == PressureHistoryAvailability.AVAILABLE &&
		coverage == PressureHistoryCoverage.COMPLETE &&
		windows.isNotEmpty()
	) {
		HistoryProductState.READY
	} else {
		HistoryProductState.PARTIAL
	}
}

private fun PressureHistoryCoverage.toPublicCoverage(): ApiPressureHistoryCoverage = when (this) {
	PressureHistoryCoverage.NONE -> ApiPressureHistoryCoverage.NONE
	PressureHistoryCoverage.COMPLETE -> ApiPressureHistoryCoverage.COMPLETE
	PressureHistoryCoverage.PARTIAL -> ApiPressureHistoryCoverage.PARTIAL
	PressureHistoryCoverage.UNKNOWN -> ApiPressureHistoryCoverage.UNKNOWN
}

private fun PressureHistoryWindow.toPublicWindow() = ApiPressureHistoryWindow(
	intervalStartTime = EpochMs(intervalStartTimeMs),
	intervalEndTime = EpochMs(intervalEndTimeMs),
	sampleCount = sampleCount,
	expectedSampleCount = expectedSampleCount,
	meanHectopascals = meanHectopascals,
	sumSquaredDeviations = sumSquaredDeviations,
	minimumHectopascals = minimumHectopascals,
	maximumHectopascals = maximumHectopascals,
	firstHectopascals = firstHectopascals,
	latestHectopascals = lastHectopascals,
	slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
	rSquared = rSquared,
	sensorAccuracy = sensorAccuracy.toPublicSensorAccuracy(),
	effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
	effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
	targetWindowDurationNanos = targetWindowDurationNanos,
	maximumInterSampleGapNanos = maximumInterSampleGapNanos,
	closure = closureKind.toPublicClosure(),
	qualification = qualification.toPublicQualification(),
	sourceQualityFlags = sourceQualityFlags,
	sourceQualityConfidence = sourceQualityConfidence,
	zoneId = zoneId,
)

private fun String.toPublicSensorAccuracy(): PressureSensorAccuracy = when (this) {
	PressureFactRevisionEntity.SENSOR_ACCURACY_UNKNOWN -> PressureSensorAccuracy.UNKNOWN
	PressureFactRevisionEntity.SENSOR_ACCURACY_UNRELIABLE -> PressureSensorAccuracy.UNRELIABLE
	PressureFactRevisionEntity.SENSOR_ACCURACY_LOW -> PressureSensorAccuracy.LOW
	PressureFactRevisionEntity.SENSOR_ACCURACY_MEDIUM -> PressureSensorAccuracy.MEDIUM
	PressureFactRevisionEntity.SENSOR_ACCURACY_HIGH -> PressureSensorAccuracy.HIGH
	else -> error("Qualified Pressure history contained unknown sensor accuracy")
}

private fun String.toPublicClosure(): PressureWindowClosure = when (this) {
	PressureFactRevisionEntity.CLOSURE_TARGET_ELAPSED -> PressureWindowClosure.TARGET_ELAPSED
	PressureFactRevisionEntity.CLOSURE_SOURCE_BOUNDARY -> PressureWindowClosure.SOURCE_BOUNDARY
	else -> error("Qualified Pressure history contained unknown closure authority")
}

private fun String.toPublicQualification(): PressureWindowQualification = when (this) {
	PressureFactRevisionEntity.QUALIFICATION_COMPLETE -> PressureWindowQualification.COMPLETE
	PressureFactRevisionEntity.QUALIFICATION_PARTIAL -> PressureWindowQualification.PARTIAL
	else -> error("Qualified Pressure history contained unknown qualification")
}

// Exhaustiveness prevents a new selector failure from silently collapsing into a generic state.
@Suppress("CyclomaticComplexMethod")
internal fun PressureHistoryReason.toPublicCause(): PressureHistoryCause = when (this) {
	PressureHistoryReason.SOURCE_NOT_CAPTURED -> PressureHistoryCause.SOURCE_NOT_CAPTURED
	PressureHistoryReason.LEGACY_UNATTRIBUTED -> PressureHistoryCause.LEGACY_UNATTRIBUTED
	PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE ->
		PressureHistoryCause.SEGMENT_MEMBERSHIP_INCOMPLETE
	PressureHistoryReason.LOGICAL_MANIFEST_REVISION_UNION_INVALID ->
		PressureHistoryCause.LOGICAL_MANIFEST_REVISION_UNION_INVALID
	PressureHistoryReason.SERVICE_RUN_MISSING -> PressureHistoryCause.SERVICE_RUN_MISSING
	PressureHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH ->
		PressureHistoryCause.SERVICE_RUN_MEMBERSHIP_MISMATCH
	PressureHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE ->
		PressureHistoryCause.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE
	PressureHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH ->
		PressureHistoryCause.SERVICE_RUN_SEGMENT_BINDING_MISMATCH
	PressureHistoryReason.MANIFEST_MISSING -> PressureHistoryCause.MANIFEST_MISSING
	PressureHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH ->
		PressureHistoryCause.MANIFEST_MEMBERSHIP_MISMATCH
	PressureHistoryReason.MANIFEST_INTEGRITY_FAILED ->
		PressureHistoryCause.MANIFEST_INTEGRITY_FAILED
	PressureHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID ->
		PressureHistoryCause.SOURCE_POLICY_ATTRIBUTION_INVALID
	PressureHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN ->
		PressureHistoryCause.MIXED_WRITER_WITHIN_SERVICE_RUN
	PressureHistoryReason.UNKNOWN_WRITER -> PressureHistoryCause.UNKNOWN_WRITER
	PressureHistoryReason.PRODUCT_LANE_MISSING -> PressureHistoryCause.PRODUCT_LANE_MISSING
	PressureHistoryReason.PRODUCT_LANE_INVALID -> PressureHistoryCause.PRODUCT_LANE_INVALID
	PressureHistoryReason.SOURCE_EVIDENCE_STATE_MISSING ->
		PressureHistoryCause.SOURCE_EVIDENCE_STATE_MISSING
	PressureHistoryReason.OUTSIDE_RETAINED_FLOOR -> PressureHistoryCause.OUTSIDE_RETAINED_FLOOR
	PressureHistoryReason.RETENTION_CROSSES_SEGMENT ->
		PressureHistoryCause.RETENTION_CROSSES_SEGMENT
	PressureHistoryReason.RETENTION_TRUNCATED -> PressureHistoryCause.RETENTION_TRUNCATED
	PressureHistoryReason.RETENTION_TRUNCATION_MARKER_INVALID ->
		PressureHistoryCause.RETENTION_TRUNCATION_MARKER_INVALID
	PressureHistoryReason.COMPLETENESS_MISSING -> PressureHistoryCause.COMPLETENESS_MISSING
	PressureHistoryReason.COMPLETENESS_INVALID -> PressureHistoryCause.COMPLETENESS_INVALID
	PressureHistoryReason.APP_DRAIN_INCOMPLETE -> PressureHistoryCause.APP_DRAIN_INCOMPLETE
	PressureHistoryReason.STOP_INCOMPLETE -> PressureHistoryCause.STOP_INCOMPLETE
	PressureHistoryReason.UNRESOLVED_PROVIDER_SEQUENCE ->
		PressureHistoryCause.UNRESOLVED_PROVIDER_SEQUENCE
	PressureHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE ->
		PressureHistoryCause.PROVIDER_COMPLETENESS_UNOBSERVABLE
	PressureHistoryReason.PROVIDER_UNAVAILABLE -> PressureHistoryCause.PROVIDER_UNAVAILABLE
	PressureHistoryReason.UNAVAILABLE_SENTINEL_WITH_RETAINED_FACTS ->
		PressureHistoryCause.UNAVAILABLE_SENTINEL_WITH_RETAINED_FACTS
	PressureHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN ->
		PressureHistoryCause.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN
	PressureHistoryReason.SERVICE_RUN_ACTIVE -> PressureHistoryCause.SERVICE_RUN_ACTIVE
	PressureHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN ->
		PressureHistoryCause.FACTS_MISSING_FOR_ADMITTED_RUN
	PressureHistoryReason.PRESSURE_FACT_INTEGRITY_FAILED ->
		PressureHistoryCause.PRESSURE_FACT_INTEGRITY_FAILED
	PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE ->
		PressureHistoryCause.PRESSURE_FACT_CORRECTION_INCOMPLETE
	PressureHistoryReason.STALE_COLLECTED_DATA_EPOCH ->
		PressureHistoryCause.STALE_COLLECTED_DATA_EPOCH
	PressureHistoryReason.PARTIAL_FACT -> PressureHistoryCause.PARTIAL_FACT
	PressureHistoryReason.TARGET_BEFORE_LANE_ACTIVATION ->
		PressureHistoryCause.TARGET_BEFORE_LANE_ACTIVATION
	PressureHistoryReason.TERMINAL_PROJECTION_FAILURE ->
		PressureHistoryCause.TERMINAL_PROJECTION_FAILURE
	PressureHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET ->
		PressureHistoryCause.PRODUCT_LANE_CUTOFF_BEFORE_TARGET
	PressureHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET ->
		PressureHistoryCause.PRODUCT_LANE_RETIRED_BEFORE_TARGET
	PressureHistoryReason.PRODUCT_LANE_BEHIND -> PressureHistoryCause.PRODUCT_LANE_BEHIND
	PressureHistoryReason.DELETED_FACTS -> PressureHistoryCause.DELETED_FACTS
	PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW ->
		PressureHistoryCause.BATCH_DEPENDENCY_OVERFLOW
}
