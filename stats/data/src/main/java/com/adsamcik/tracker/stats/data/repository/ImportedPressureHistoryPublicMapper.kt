package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportedPressureHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureCoverage
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureHistoryWindow
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs

internal fun ImportedPressureHistoryEvaluation.toPublicPressureOnlyEntry(): PressureOnlyHistoryEntry =
	when (this) {
		is ImportedPressureHistoryEvaluation.Readable -> toPublicReadableEntry()
		is ImportedPressureHistoryEvaluation.Unverifiable -> PressureOnlyHistoryEntry(
			key = importedHistoryKey(candidate.identity),
			origin = importedOrigin(candidate.identity),
			startTime = EpochMs(candidate.startTimeMs),
			endTime = EpochMs(candidate.endTimeMs),
			pressure = PressureHistory(
				availability = HistoryAvailability.UNAVAILABLE,
				evidence = HistoryEvidence.NONE,
				productState = HistoryProductState.FAILED,
				coverage = PressureHistoryCoverage.UNKNOWN,
				windows = emptyList(),
				causes = setOf(reason.toPublicCause()),
			),
		)
	}

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun ImportedPressureHistoryEvaluation.Readable.toPublicReadableEntry():
	PressureOnlyHistoryEntry {
	val entry = latest.entry
	val locallyDeletedRuns = deletedRunIdentities
	val sourceDeletedRuns = entry.runs.filter {
		it.availability == PortablePressureAvailability.DELETED
	}.mapTo(linkedSetOf()) { it.identity.value }
	val allDeletedRuns = locallyDeletedRuns + sourceDeletedRuns
	val visibleRuns = entry.runs.filterNot { it.identity.value in allDeletedRuns }
	val outsideRetainedFloor = retainedFromMs?.let { entry.endTimeMs < it } == true
	val retentionCrossesEntry = retainedFromMs?.let {
		entry.startTimeMs < it && entry.endTimeMs >= it
	} == true
	val windows = if (outsideRetainedFloor) {
		emptyList()
	} else {
		visibleRuns.flatMap { run ->
			run.windows.filter { window ->
				retainedFromMs?.let { window.intervalEndTimeMs >= it } != false
			}
		}.map(PortablePressureWindowV1::toPublicWindow)
	}
	val hasDeletion = allDeletedRuns.isNotEmpty()
	val hasRetentionLoss = visibleRuns.any { it.retentionLoss }
	val coverage = visibleRuns.toPublicCoverage(
		hasWindows = windows.isNotEmpty(),
		hasDeletion = hasDeletion,
		retentionLimited = outsideRetainedFloor || retentionCrossesEntry,
	)
	val causes = linkedSetOf<PressureHistoryCause>()
	if (hasDeletion) causes += PressureHistoryCause.DELETED_FACTS
	if (outsideRetainedFloor) causes += PressureHistoryCause.OUTSIDE_RETAINED_FLOOR
	if (retentionCrossesEntry) causes += PressureHistoryCause.RETENTION_CROSSES_SEGMENT
	if (hasRetentionLoss) causes += PressureHistoryCause.RETENTION_TRUNCATED
	if (windows.isNotEmpty() && visibleRuns.any {
		it.availability == PortablePressureAvailability.RETAINED &&
			(it.coverage != PortablePressureCoverage.COMPLETE || it.windows.any { window ->
				window.qualification == PortablePressureWindowQualification.PARTIAL
			})
	}) causes += PressureHistoryCause.PARTIAL_FACT
	if (windows.isEmpty() && causes.isEmpty()) {
		causes += if (visibleRuns.all {
			it.availability == PortablePressureAvailability.DISABLED
		}) {
			PressureHistoryCause.SOURCE_NOT_CAPTURED
		} else {
			PressureHistoryCause.PROVIDER_UNAVAILABLE
		}
	}
	val productState = if (
		windows.isNotEmpty() && coverage == PressureHistoryCoverage.COMPLETE && causes.isEmpty()
	) {
		HistoryProductState.READY
	} else if (windows.isNotEmpty() || hasRetentionLoss) {
		HistoryProductState.PARTIAL
	} else {
		HistoryProductState.DEGRADED
	}
	return PressureOnlyHistoryEntry(
		key = importedHistoryKey(entry.identity.value),
		origin = importedOrigin(entry.identity.value),
		startTime = EpochMs(entry.startTimeMs),
		endTime = EpochMs(entry.endTimeMs),
		pressure = PressureHistory(
			availability = when {
				windows.isNotEmpty() || hasRetentionLoss -> HistoryAvailability.AVAILABLE
				visibleRuns.isNotEmpty() && visibleRuns.all {
					it.availability == PortablePressureAvailability.DISABLED
				} -> HistoryAvailability.DISABLED
				else -> HistoryAvailability.UNAVAILABLE
			},
			evidence = if (windows.isEmpty()) HistoryEvidence.NONE else HistoryEvidence.RECORDED,
			productState = productState,
			coverage = coverage,
			windows = windows,
			causes = causes,
		),
	)
}

private fun List<PortablePressureRunV1>.toPublicCoverage(
	hasWindows: Boolean,
	hasDeletion: Boolean,
	retentionLimited: Boolean,
): PressureHistoryCoverage = when {
	hasWindows && !hasDeletion && !retentionLimited && isNotEmpty() && all {
		it.coverage == PortablePressureCoverage.COMPLETE
	} -> PressureHistoryCoverage.COMPLETE
	hasWindows || any { it.retentionLoss || it.coverage == PortablePressureCoverage.PARTIAL } ->
		PressureHistoryCoverage.PARTIAL
	any { it.coverage == PortablePressureCoverage.UNKNOWN } -> PressureHistoryCoverage.UNKNOWN
	else -> PressureHistoryCoverage.NONE
}

@Suppress("LongMethod")
private fun PortablePressureWindowV1.toPublicWindow() = PressureHistoryWindow(
	intervalStartTime = EpochMs(intervalStartTimeMs),
	intervalEndTime = EpochMs(intervalEndTimeMs),
	sampleCount = sampleCount,
	expectedSampleCount = expectedSampleCount,
	meanHectopascals = meanHectopascals,
	sumSquaredDeviations = sumSquaredDeviations,
	minimumHectopascals = minimumHectopascals,
	maximumHectopascals = maximumHectopascals,
	firstHectopascals = firstHectopascals,
	latestHectopascals = latestHectopascals,
	slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
	rSquared = rSquared,
	sensorAccuracy = sensorAccuracy.toPublicAccuracy(),
	effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
	effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
	targetWindowDurationNanos = targetWindowDurationNanos,
	maximumInterSampleGapNanos = maximumInterSampleGapNanos,
	closure = closure.toPublicClosure(),
	qualification = qualification.toPublicQualification(),
	sourceQualityFlags = sourceQualityFlags,
	sourceQualityConfidence = sourceQualityConfidence,
	zoneId = zoneId,
)

private fun PortablePressureSensorAccuracy.toPublicAccuracy(): PressureSensorAccuracy = when (this) {
	PortablePressureSensorAccuracy.UNKNOWN -> PressureSensorAccuracy.UNKNOWN
	PortablePressureSensorAccuracy.UNRELIABLE -> PressureSensorAccuracy.UNRELIABLE
	PortablePressureSensorAccuracy.LOW -> PressureSensorAccuracy.LOW
	PortablePressureSensorAccuracy.MEDIUM -> PressureSensorAccuracy.MEDIUM
	PortablePressureSensorAccuracy.HIGH -> PressureSensorAccuracy.HIGH
}

private fun PortablePressureWindowClosure.toPublicClosure(): PressureWindowClosure = when (this) {
	PortablePressureWindowClosure.TARGET_ELAPSED -> PressureWindowClosure.TARGET_ELAPSED
	PortablePressureWindowClosure.SOURCE_BOUNDARY -> PressureWindowClosure.SOURCE_BOUNDARY
}

private fun PortablePressureWindowQualification.toPublicQualification():
	PressureWindowQualification = when (this) {
	PortablePressureWindowQualification.COMPLETE -> PressureWindowQualification.COMPLETE
	PortablePressureWindowQualification.PARTIAL -> PressureWindowQualification.PARTIAL
}

private fun ImportedPressureHistoryFailure.toPublicCause(): PressureHistoryCause = when (this) {
	ImportedPressureHistoryFailure.SOURCE_EVIDENCE_STATE_MISSING ->
		PressureHistoryCause.IMPORTED_SOURCE_EVIDENCE_STATE_MISSING
	ImportedPressureHistoryFailure.STALE_COLLECTED_DATA_EPOCH ->
		PressureHistoryCause.IMPORTED_STALE_COLLECTED_DATA_EPOCH
	ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE ->
		PressureHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
	ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW ->
		PressureHistoryCause.IMPORTED_DEPENDENCY_OVERFLOW
}

private fun importedOrigin(identity: String) = PressureHistoryOrigin.Imported(
	ImportedPressureHistoryIdentity(identity),
)

private fun importedHistoryKey(identity: String) =
	TrackingHistoryEntryKey("pressure:imported:$identity")
