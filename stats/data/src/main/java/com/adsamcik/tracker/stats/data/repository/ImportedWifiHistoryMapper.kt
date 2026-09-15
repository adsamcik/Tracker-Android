package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductFailure
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiAcquisitionCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableWifiResultCompleteness
import com.adsamcik.tracker.stats.api.repository.PortableWifiRunAvailability
import com.adsamcik.tracker.stats.api.repository.WifiHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryObservation
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness
import com.adsamcik.tracker.stats.api.repository.WifiHistorySignalQuality
import com.adsamcik.tracker.stats.api.value.EpochMs

internal fun ImportedWifiProductEvaluation.toPublicWifiEntry(
	originConflict: Boolean = false,
): WifiHistoryEntry = when (this) {
	is ImportedWifiProductEvaluation.Unverifiable -> when (val cause = reason.toPublicCause()) {
		WifiHistoryCause.PRIVACY_EPOCH_MISMATCH -> unavailable(cause)
		else -> failed(if (originConflict) WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT else cause)
	}
	is ImportedWifiProductEvaluation.Readable -> when {
		originConflict -> failed(WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT)
		entryDeleted -> deleted()
		else -> readablePublicEntry()
	}
}

private fun ImportedWifiProductEvaluation.Readable.readablePublicEntry(): WifiHistoryEntry {
	val visibleRuns = entry.runs.filterNot { it.identity in deletedRunIdentities }
	if (visibleRuns.isEmpty()) return deleted()
	val retained = retainedObservationIdentities
	val visibleObservations = visibleRuns.flatMap { run ->
		run.observations.filter { it.identity in retained }
	}
	if (visibleObservations.isEmpty()) {
		return when {
			deletedRunIdentities.isNotEmpty() -> deleted()
			retentionLimited || visibleRuns.any { it.retentionLoss } ->
				unavailable(WifiHistoryCause.RETENTION_LIMIT)
			visibleRuns.all { it.captureCoverage == PortableWifiCaptureCoverage.NOT_CAPTURED } ->
				unavailable(WifiHistoryCause.SOURCE_NOT_CAPTURED)
			visibleRuns.any {
				it.availability == PortableWifiRunAvailability.NO_RETAINED_OBSERVATION
			} -> missing(WifiHistoryCause.NO_QUALIFIED_FACTS)
			else -> unavailable(WifiHistoryCause.PROVIDER_UNAVAILABLE)
		}
	}

	val causes = linkedSetOf<WifiHistoryCause>()
	if (deletedRunIdentities.isNotEmpty()) causes += WifiHistoryCause.DELETED
	if (visibleRuns.any { it.captureCoverage == PortableWifiCaptureCoverage.NOT_CAPTURED }) {
		causes += WifiHistoryCause.SOURCE_NOT_CAPTURED
	}
	if (visibleRuns.any {
			it.captureCoverage == PortableWifiCaptureCoverage.PARTIAL_RUN ||
				it.acquisitionCompleteness != PortableWifiAcquisitionCompleteness.COMPLETE ||
				it.hasUnresolvedProviderRange
		}
	) causes += WifiHistoryCause.ACQUISITION_INCOMPLETE
	if (retentionLimited || visibleRuns.any { it.retentionLoss }) {
		causes += WifiHistoryCause.RETENTION_LIMIT
	}
	if (visibleObservations.any {
			it.resultCompleteness == PortableWifiResultCompleteness.PARTIAL
		}
	) causes += WifiHistoryCause.RESULT_SET_PARTIAL
	val state = if (causes.isEmpty()) WifiHistoryProductState.READY else WifiHistoryProductState.PARTIAL
	val coverage = if (causes.isEmpty()) WifiHistoryCoverage.COMPLETE else WifiHistoryCoverage.PARTIAL
	return WifiHistoryEntry(
		key = importedKey(candidate.identity.value),
		startTime = EpochMs(entry.startTimeMs),
		endTime = EpochMs(entry.endTimeMs),
		storedZoneIds = visibleRuns.flatMapTo(linkedSetOf()) { it.storedZoneIds },
		state = state,
		coverage = coverage,
		observations = visibleObservations.map(PortableCapturedWifiObservationV1::toPublicObservation),
		causes = causes,
		origin = WifiHistoryOrigin.IMPORTED,
		importedSelection = candidate.selection,
	)
}

private fun PortableCapturedWifiObservationV1.toPublicObservation(): WifiHistoryObservation {
	val rejected = Math.addExact(
		Math.addExact(staleResultCount, clockUnverifiableResultCount),
		malformedResultCount,
	)
	val bands = linkedMapOf<WifiHistoryBand, Int>()
	fun add(band: WifiHistoryBand, count: Int) {
		if (count > 0) bands[band] = count
	}
	add(WifiHistoryBand.TWO_POINT_FOUR_GHZ, twoPointFourGhzCount)
	add(WifiHistoryBand.FIVE_GHZ, fiveGhzCount)
	add(WifiHistoryBand.SIX_GHZ, sixGhzCount)
	add(WifiHistoryBand.OTHER, otherBandCount)
	return WifiHistoryObservation(
		intervalStartTime = EpochMs(coverageStartTimeMs),
		observedTime = EpochMs(observedTimeMs),
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		availability = WifiHistoryAvailability.AVAILABLE,
		resultCompleteness = WifiHistoryResultCompleteness.valueOf(resultCompleteness.name),
		submittedResultCount = submittedResultCount,
		acceptedResultCount = acceptedResultCount,
		rejectedResultCount = rejected,
		observationCount = observationCount,
		bandMix = bands,
		signalQuality = WifiHistorySignalQuality(
			strongestSignalDbm,
			weakestSignalDbm,
			meanSignalDbm,
			observationCount,
		),
		sourceQualityFlags = sourceQualityFlags,
		sourceQualityConfidence = sourceQualityConfidence,
		storedZoneId = storedZoneId,
	)
}

private fun ImportedWifiProductEvaluation.failed(cause: WifiHistoryCause) =
	publicShell(WifiHistoryProductState.FAILED, cause)

private fun ImportedWifiProductEvaluation.unavailable(cause: WifiHistoryCause) =
	publicShell(WifiHistoryProductState.UNAVAILABLE, cause, authenticatedSelection())

private fun ImportedWifiProductEvaluation.missing(cause: WifiHistoryCause) =
	publicShell(WifiHistoryProductState.MISSING, cause, authenticatedSelection())

private fun ImportedWifiProductEvaluation.deleted() =
	publicShell(
		WifiHistoryProductState.DELETED,
		WifiHistoryCause.DELETED,
		authenticatedSelection(),
	)

private fun ImportedWifiProductEvaluation.publicShell(
	state: WifiHistoryProductState,
	cause: WifiHistoryCause,
	selection: com.adsamcik.tracker.stats.api.repository.WifiImportedHistorySelection? = null,
) = WifiHistoryEntry(
	key = importedKey(candidate.identity.value),
	startTime = EpochMs(candidate.startTimeMs),
	endTime = EpochMs(candidate.endTimeMs),
	storedZoneIds = emptySet(),
	state = state,
	coverage = WifiHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(cause),
	origin = WifiHistoryOrigin.IMPORTED,
	importedSelection = selection,
)

private fun ImportedWifiProductEvaluation.authenticatedSelection() =
	(this as? ImportedWifiProductEvaluation.Readable)?.candidate?.selection

private fun ImportedWifiProductFailure.toPublicCause(): WifiHistoryCause = when (this) {
	ImportedWifiProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
	ImportedWifiProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	-> WifiHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
	ImportedWifiProductFailure.STALE_COLLECTED_DATA_EPOCH ->
		WifiHistoryCause.PRIVACY_EPOCH_MISMATCH
	ImportedWifiProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		WifiHistoryCause.ORIGIN_IDENTITY_CONFLICT
	ImportedWifiProductFailure.DEPENDENCY_OVERFLOW ->
		WifiHistoryCause.READ_BUDGET_EXCEEDED
	ImportedWifiProductFailure.VALUE_OVERFLOW -> WifiHistoryCause.VALUE_OVERFLOW
}

private fun importedKey(identity: String) = WifiHistoryEntryKey("wifi-imported:$identity")
