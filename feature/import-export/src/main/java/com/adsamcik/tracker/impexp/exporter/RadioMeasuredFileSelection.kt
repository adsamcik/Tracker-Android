package com.adsamcik.tracker.impexp.exporter

import com.adsamcik.tracker.impexp.portable.CellBackgroundChildV2
import com.adsamcik.tracker.impexp.portable.WifiBackgroundChildV2
import com.adsamcik.tracker.stats.api.repository.PortableAmbientCellArchiveV1
import com.adsamcik.tracker.stats.api.repository.PortableAmbientWifiArchiveV1

import com.adsamcik.tracker.impexp.portable.WifiSessionFileV2
import com.adsamcik.tracker.impexp.portable.CellSessionFileV2
import com.adsamcik.tracker.shared.base.database.selectReadings
import com.adsamcik.tracker.stats.api.repository.selectReadings

/** A real scan keeps its complete child list; distinct scans are independently selected by time. */
internal fun selectWifiSessionFile(file: WifiSessionFileV2, dates: SourceExportDates?,
    complete: Boolean): WifiSessionFileV2? {
    if (dates == null) {
        return file
    }
    val children = file.children.groupBy { it.observationIdentity }
    val selected = file.entry.selectReadings({ observation ->
        val times = children[observation.identity.value].orEmpty().map { it.observedWallTimeMs }
        readingTouchesDates(dates, observation.coverageStartTimeMs, observation.observedTimeMs, times)
    }, { run -> (run.retentionLoss || run.hasUnresolvedProviderRange) && dates.overlaps(run.startTimeMs,
        run.endTimeMs) }) ?: return null
    if (complete) {
        return file
    }
    val ids = selected.runs.flatMap { it.observations }.mapTo(hashSetOf()) { it.identity.value }
    return WifiSessionFileV2(selected, file.children.filter { it.observationIdentity in ids })
}

internal fun selectCellSessionFile(file: CellSessionFileV2, dates: SourceExportDates?,
    complete: Boolean): CellSessionFileV2? {
    if (dates == null) {
        return file
    }
    val children = file.children.groupBy { it.observationIdentity }
    val selected = file.entry.selectReadings({ observation ->
        val times = children[observation.identity.value].orEmpty().map { it.observedWallTimeMs }
        readingTouchesDates(dates, observation.coverageStartTimeMs, observation.observedTimeMs, times)
    }, { run -> run.retentionLoss && dates.overlaps(run.startTimeMs, run.endTimeMs) }) ?: return null
    if (complete) {
        return file
    }
    val ids = selected.runs.flatMap { it.observations }.mapTo(hashSetOf()) { it.identity.value }
    return CellSessionFileV2(selected, file.children.filter { it.observationIdentity in ids })
}

private fun readingTouchesDates(dates: SourceExportDates, from: Long, to: Long, childTimes: List<Long>) =
    dates.touches(childTimes.minOrNull() ?: from, childTimes.maxOrNull() ?: to)

internal fun wifiReadingsOutsideDates(file: WifiSessionFileV2, dates: SourceExportDates?): Int {
    if (dates == null) {
        return 0
    }
    val childIds = file.children.mapTo(hashSetOf()) { it.observationIdentity }
    return file.children.count { !dates.contains(it.observedWallTimeMs) } +
        file.entry.runs.sumOf { run -> run.observations.count { it.identity.value !in childIds &&
            !dates.contains(it.observedTimeMs) } }
}

internal fun cellReadingsOutsideDates(file: CellSessionFileV2, dates: SourceExportDates?): Int {
    if (dates == null) {
        return 0
    }
    val childIds = file.children.mapTo(hashSetOf()) { it.observationIdentity }
    return file.children.count { !dates.contains(it.observedWallTimeMs) } +
        file.entry.runs.sumOf { run -> run.observations.count { it.identity.value !in childIds &&
            !dates.contains(it.observedTimeMs) } }
}

internal fun wifiBackgroundReadingsOutsideDates(archive: PortableAmbientWifiArchiveV1?,
    children: List<WifiBackgroundChildV2>, dates: SourceExportDates?): Int {
    if (archive == null || dates == null) {
        return 0
    }
    val childIds = children.mapTo(hashSetOf()) { it.factIdentity }
    return children.count { !dates.contains(it.observedWallTimeMs) } +
        archive.facts.count { it.identity !in childIds && !dates.contains(it.observedTimeMs) }
}

internal fun cellBackgroundReadingsOutsideDates(archive: PortableAmbientCellArchiveV1?,
    children: List<CellBackgroundChildV2>, dates: SourceExportDates?): Int {
    if (archive == null || dates == null) {
        return 0
    }
    val childIds = children.mapTo(hashSetOf()) { it.factIdentity }
    return children.count { !dates.contains(it.observedWallTimeMs) } +
        archive.facts.count { it.identity !in childIds && !dates.contains(it.observedTimeMs) }
}
