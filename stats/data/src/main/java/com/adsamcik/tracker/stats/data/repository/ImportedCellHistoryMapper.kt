package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCellAcquisitionCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableCellChildCompleteness
import com.adsamcik.tracker.shared.base.database.PortableCellRunAvailability
import com.adsamcik.tracker.stats.api.repository.CellHistoryAvailability
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryChildCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryObservation
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistorySignalQuality
import com.adsamcik.tracker.stats.api.repository.CellHistorySubscriptionGrouping
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs

internal fun ImportedCellProductEvaluation.toPublicCellEntry(
	overrideFailure: CellHistoryCause? = null,
): CellHistoryEntry = when {
	overrideFailure != null -> publicShell(
		state = CellHistoryProductState.UNVERIFIABLE,
		cause = overrideFailure,
	)
	this is ImportedCellProductEvaluation.Unverifiable -> publicShell(
		state = CellHistoryProductState.UNVERIFIABLE,
		cause = reason.toPublicCause(),
	)
	this is ImportedCellProductEvaluation.Readable -> toReadablePublicEntry()
	else -> error("Unknown imported Cell evaluation")
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun ImportedCellProductEvaluation.Readable.toReadablePublicEntry(): CellHistoryEntry {
	if (entryDeleted) return publicShell(CellHistoryProductState.DELETED, CellHistoryCause.DELETED)
	if (retentionLimited) {
		return publicShell(CellHistoryProductState.UNAVAILABLE, CellHistoryCause.RETENTION_LIMIT)
	}
	val visibleRuns = entry.runs.filterNot { it.identity.value in deletedRunIdentities }
	val observations = visibleRuns.flatMap { run -> run.observations }.map(
		PortableCapturedCellObservationV1::toPublicObservation,
	)
	if (observations.isEmpty()) {
		return when {
			deletedRunIdentities.isNotEmpty() ->
				publicShell(CellHistoryProductState.DELETED, CellHistoryCause.DELETED)
			visibleRuns.any { it.retentionLoss } ->
				publicShell(CellHistoryProductState.UNAVAILABLE, CellHistoryCause.RETENTION_LIMIT)
			visibleRuns.isNotEmpty() && visibleRuns.all {
				it.captureCoverage == PortableCellCaptureCoverage.NOT_CAPTURED
			} -> publicShell(
				CellHistoryProductState.UNAVAILABLE,
				CellHistoryCause.SOURCE_NOT_CAPTURED,
			)
			visibleRuns.any {
				it.availability == PortableCellRunAvailability.NO_RETAINED_OBSERVATION
			} -> publicShell(
				CellHistoryProductState.UNAVAILABLE,
				CellHistoryCause.PROVIDER_UNAVAILABLE,
			)
			else -> publicShell(
				CellHistoryProductState.UNAVAILABLE,
				CellHistoryCause.NO_QUALIFIED_FACTS,
			)
		}
	}

	val causes = linkedSetOf<CellHistoryCause>()
	if (deletedRunIdentities.isNotEmpty()) causes += CellHistoryCause.DELETED
	if (visibleRuns.any { it.captureCoverage == PortableCellCaptureCoverage.NOT_CAPTURED }) {
		causes += CellHistoryCause.SOURCE_NOT_CAPTURED
	}
	if (visibleRuns.any {
			it.captureCoverage == PortableCellCaptureCoverage.PARTIAL_RUN ||
				it.acquisitionCompleteness != PortableCellAcquisitionCompleteness.COMPLETE
		}) {
		causes += CellHistoryCause.ACQUISITION_INCOMPLETE
	}
	if (visibleRuns.any { it.retentionLoss }) causes += CellHistoryCause.RETENTION_LIMIT
	if (visibleRuns.flatMap { it.observations }.any {
			it.childCompleteness == PortableCellChildCompleteness.PARTIAL
		}) {
		causes += CellHistoryCause.CHILDREN_PARTIAL
	}
	// Captured Cell v1 intentionally does not claim complete subscription grouping.
	causes += CellHistoryCause.SUBSCRIPTION_GROUPING_UNKNOWN
	val selection = importedSelection()
	return CellHistoryEntry(
		key = importedKey(candidate.identity),
		startTime = EpochMs(entry.startTimeMs),
		endTime = EpochMs(entry.endTimeMs),
		storedZoneIds = observations.mapTo(linkedSetOf(), CellHistoryObservation::storedZoneId),
		state = if (causes.isEmpty()) CellHistoryProductState.READY else CellHistoryProductState.PARTIAL,
		coverage = if (causes.isEmpty()) CellHistoryCoverage.COMPLETE else CellHistoryCoverage.PARTIAL,
		observations = observations,
		causes = causes,
		origin = CellHistoryOrigin.Imported(selection),
		selection = selection,
	)
}

private fun PortableCapturedCellObservationV1.toPublicObservation(): CellHistoryObservation {
	val rejected = listOf(
		staleChildCount,
		futureTimeChildCount,
		missingTimeChildCount,
		clockUnverifiableChildCount,
		authorityMismatchChildCount,
		unsupportedTechnologyChildCount,
	).fold(0, Math::addExact)
	val technologies = linkedMapOf<CellHistoryTechnology, Int>()
	fun include(technology: CellHistoryTechnology, count: Int) {
		if (count > 0) technologies[technology] = count
	}
	include(CellHistoryTechnology.GSM, gsmCount)
	include(CellHistoryTechnology.CDMA, cdmaCount)
	include(CellHistoryTechnology.WCDMA, wcdmaCount)
	include(CellHistoryTechnology.TDSCDMA, tdscdmaCount)
	include(CellHistoryTechnology.LTE, lteCount)
	include(CellHistoryTechnology.NR, nrCount)
	return CellHistoryObservation(
		intervalStartTime = EpochMs(coverageStartTimeMs),
		observedTime = EpochMs(observedTimeMs),
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		availability = CellHistoryAvailability.AVAILABLE,
		subscriptionGrouping = CellHistorySubscriptionGrouping.UNKNOWN,
		childCompleteness = when (childCompleteness) {
			PortableCellChildCompleteness.COMPLETE -> CellHistoryChildCompleteness.COMPLETE
			PortableCellChildCompleteness.PARTIAL -> CellHistoryChildCompleteness.PARTIAL
		},
		submittedChildCount = submittedChildCount,
		acceptedChildCount = acceptedChildCount,
		rejectedChildCount = rejected,
		registeredObservationCount = registeredObservationCount,
		technologyMix = technologies,
		signalQuality = CellHistorySignalQuality(
			unknownCount = qualityUnknownCount,
			noneOrUnknownCount = qualityNoneOrUnknownCount,
			poorCount = qualityPoorCount,
			moderateCount = qualityModerateCount,
			goodCount = qualityGoodCount,
			greatCount = qualityGreatCount,
		),
		weakObservationCount = weakObservationCount,
		allKnownQualityIsWeak = allKnownQualityIsWeak,
		sourceQualityFlags = qualityFlags,
		sourceQualityConfidence = qualityConfidence?.toFloat(),
		storedZoneId = storedZoneId,
	)
}

private fun ImportedCellProductEvaluation.publicShell(
	state: CellHistoryProductState,
	cause: CellHistoryCause,
) = importedSelection().let { selection ->
	CellHistoryEntry(
		key = importedKey(candidate.identity),
		startTime = EpochMs(candidate.startTimeMs),
		endTime = EpochMs(candidate.endTimeMs),
		storedZoneIds = emptySet(),
		state = state,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(cause),
		origin = CellHistoryOrigin.Imported(selection),
		selection = selection,
	)
}

private fun ImportedCellProductEvaluation.importedSelection() =
	ImportedCellHistorySelection(
		identity = ImportedCellHistoryIdentity(candidate.identity),
		importRevision = candidate.importRevision,
		contentChecksum = ImportedCellHistoryDigest(candidate.contentChecksum),
	)

private fun ImportedCellProductFailure.toPublicCause(): CellHistoryCause = when (this) {
	ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
	ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	ImportedCellProductFailure.VALUE_OVERFLOW,
	-> CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
	ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH ->
		CellHistoryCause.IMPORTED_PRIVACY_EPOCH_MISMATCH
	ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
	ImportedCellProductFailure.DEPENDENCY_OVERFLOW ->
		CellHistoryCause.READ_BUDGET_EXCEEDED
}

private fun importedKey(identity: String) = CellHistoryEntryKey("cell-imported:$identity")
