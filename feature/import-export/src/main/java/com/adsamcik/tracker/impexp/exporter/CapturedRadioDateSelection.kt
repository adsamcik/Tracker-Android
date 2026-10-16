package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.RadioSourceFileV2
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.selectReadings
import com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.selectReadings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One shared raw-row ceiling across native and imported details; no partial owner is returned. */
internal suspend fun <T> readBoundedBackgroundRadioChildren(
	factRevisions: List<Pair<String, Long>>,
	maximumRows: Int = RadioSourceFileV2.MAX_CHILDREN,
	readRevision: suspend (factId: String, semanticRevision: Long, limit: Int) -> List<T>,
): List<T> {
	require(maximumRows in 0..RadioSourceFileV2.MAX_CHILDREN)
	val rows = mutableListOf<T>()
	for ((factId, revision) in factRevisions) {
		currentCoroutineContext().ensureActive()
		val remaining = maximumRows - rows.size
		val page = readRevision(factId, revision, remaining + 1)
		currentCoroutineContext().ensureActive()
		require(page.size <= remaining) { "Too many background radio details" }
		rows += page
	}
	return rows
}

/** The result describes selected data; it does not expand or alter a saved radio entry. */
internal data class CapturedRadioDateDecision(
	val include: Boolean,
	val observationsOutsideDates: Int,
	val excludedAcrossDates: Boolean,
)

internal fun selectCapturedWifiEntry(
	entry: PortableCapturedWifiEntryV1,
	dates: SourceExportDates?,
	includeSessionsAcrossDates: Boolean,
): CapturedRadioDateDecision = decideCapturedRadioEntry(
	entryStartMs = entry.startTimeMs,
	entryEndMs = entry.endTimeMs,
	dates = dates,
	includeSessionsAcrossDates = includeSessionsAcrossDates,
	observations = entry.runs.flatMap { run ->
		run.observations.map { observation ->
			RadioObservationTime(
				observation.coverageStartTimeMs,
				observation.observedTimeMs,
				observation.latestPossibleTimeMs,
			)
		}
	},
	// An empty run alone is not a recorded outage; only explicit loss/unresolved markers count.
	recordedGaps = entry.runs.filter { run ->
		run.captureCoverage != PortableWifiCaptureCoverage.NOT_CAPTURED &&
			(run.retentionLoss || run.hasUnresolvedProviderRange)
	}.map { run -> run.startTimeMs to run.endTimeMs },
)

internal fun selectCapturedCellEntry(
	entry: PortableCapturedCellEntryV1,
	dates: SourceExportDates?,
	includeSessionsAcrossDates: Boolean,
): CapturedRadioDateDecision = decideCapturedRadioEntry(
	entryStartMs = entry.startTimeMs,
	entryEndMs = entry.endTimeMs,
	dates = dates,
	includeSessionsAcrossDates = includeSessionsAcrossDates,
	observations = entry.runs.flatMap { run ->
		run.observations.map { observation ->
			RadioObservationTime(
				observation.coverageStartTimeMs,
				observation.observedTimeMs,
				observation.latestPossibleTimeMs,
			)
		}
	},
	// An empty run alone is not a recorded outage; only explicit retained loss counts.
	recordedGaps = entry.runs.filter { run ->
		run.captureCoverage != PortableCellCaptureCoverage.NOT_CAPTURED && run.retentionLoss
	}.map { run -> run.startTimeMs to run.endTimeMs },
)

internal data class RadioObservationTime(
	val firstPossibleMs: Long,
	val observedMs: Long,
	val lastPossibleMs: Long,
)

/** Source-qualified entries arrive here after their owning exporter has read them. */
internal fun decideCapturedRadioEntry(
	entryStartMs: Long,
	entryEndMs: Long,
	dates: SourceExportDates?,
	includeSessionsAcrossDates: Boolean,
	observations: List<RadioObservationTime>,
	recordedGaps: List<Pair<Long, Long>>,
): CapturedRadioDateDecision {
	if (dates == null) {
		return CapturedRadioDateDecision(true, 0, false)
	}
	require(entryStartMs >= 0L && entryEndMs >= entryStartMs)
	val sourceTouchesDates = observations.any { observation ->
		dates.touches(observation.firstPossibleMs, observation.lastPossibleMs)
	} || recordedGaps.any { (start, end) -> dates.overlaps(start, end) }
	val include = sourceTouchesDates
	return CapturedRadioDateDecision(
		include = include,
		observationsOutsideDates = if (include) {
			observations.count { !dates.contains(it.observedMs) &&
			(includeSessionsAcrossDates || dates.touches(it.firstPossibleMs, it.lastPossibleMs)) }
		} else {
			0
		},
		excludedAcrossDates = false,
	)
}

internal fun selectedWifiReadings(entry: PortableCapturedWifiEntryV1, dates: SourceExportDates?,
	includeCompleteSessions: Boolean): PortableCapturedWifiEntryV1? {
	if (!selectCapturedWifiEntry(entry, dates, includeCompleteSessions).include) {
		return null
	}
	if (dates == null || includeCompleteSessions) {
		return entry
	}
	return entry.selectReadings({ dates.touches(it.coverageStartTimeMs, it.latestPossibleTimeMs) },
		{ (it.retentionLoss || it.hasUnresolvedProviderRange) && dates.overlaps(it.startTimeMs, it.endTimeMs) })
}

internal fun selectedCellReadings(entry: PortableCapturedCellEntryV1, dates: SourceExportDates?,
	includeCompleteSessions: Boolean): PortableCapturedCellEntryV1? {
	if (!selectCapturedCellEntry(entry, dates, includeCompleteSessions).include) {
		return null
	}
	if (dates == null || includeCompleteSessions) {
		return entry
	}
	return entry.selectReadings({ dates.touches(it.coverageStartTimeMs, it.latestPossibleTimeMs) },
		{ it.retentionLoss && dates.overlaps(it.startTimeMs, it.endTimeMs) })
}
