package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.LegacyLocationWifiCount
import com.adsamcik.tracker.shared.base.database.data.LegacyWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
import com.adsamcik.tracker.stats.api.repository.AmbientCellFact
import com.adsamcik.tracker.stats.api.repository.AmbientWifiFact
import com.adsamcik.tracker.stats.api.repository.AmbientCellCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientCellOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientCellReadResult
import com.adsamcik.tracker.stats.api.repository.AmbientWifiCoverage
import com.adsamcik.tracker.stats.api.repository.AmbientWifiOrigin
import com.adsamcik.tracker.stats.api.repository.AmbientWifiReadResult
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.CellObservedTower
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.RadioContextReadGapReason
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import com.adsamcik.tracker.stats.api.repository.WifiObservedNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Source-owned leaf selection. The outer history reader owns the Room snapshot and raw frontier. */
internal class MeasuredRadioHistoryReader(
    private val database: AppDatabase,
    private val wifi: RoomAmbientWifiRepository,
    private val cell: RoomAmbientCellRepository,
) {
    suspend fun candidates(source: HistorySource, range: HistoryRange,
        after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        if (source !in range.sources) {
            return emptyList()
        }
        val dao = database.radioObservationChildDao()
        val origins = range.origins.map { it.name }
        val purposes = range.purposes.map { it.name }
        if (origins.isEmpty() || purposes.isEmpty()) {
            return emptyList()
        }
        val radio = if (source == HistorySource.WIFI) {
            dao.wifiMeasuredHistoryCandidates(
            range.fromInclusiveMs, range.toExclusiveMs, origins, purposes,
            after?.startTimeMs, after?.originTag, after?.stableId, RADIO_HISTORY_CANDIDATE_QUERY_LIMIT,
        )
        } else {
            dao.cellMeasuredHistoryCandidates(
            range.fromInclusiveMs, range.toExclusiveMs, origins, purposes,
            after?.startTimeMs, after?.originTag, after?.stableId, RADIO_HISTORY_CANDIDATE_QUERY_LIMIT,
        )
        }
        if (source != HistorySource.WIFI || HistoryOrigin.LEGACY !in range.origins ||
            HistoryPurpose.UNKNOWN !in range.purposes) {
            return radio
        }
        val counts = database.legacyPreservedDataDao().wifiCountHistoryCandidates(
            range.fromInclusiveMs, range.toExclusiveMs, after?.startTimeMs, after?.originTag,
            after?.stableId, RADIO_HISTORY_CANDIDATE_QUERY_LIMIT)
        // Each source-local stream is bounded before the same strict global frontier is selected.
        return (radio + counts).sortedBy(::measuredHistoryPosition).take(RADIO_HISTORY_CANDIDATE_QUERY_LIMIT)
    }

    /** One physical frontier; each selected purpose still supplies its own original authority. */
    suspend fun qualify(source: HistorySource, heads: List<MeasuredHistoryCandidate>, range: HistoryRange,
        captured: MeasuredCapturedRadioHistoryReader): MeasuredRadioHistoryRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(heads.size <= MAX_RADIO_HISTORY_CANDIDATES)
        if (source !in range.sources || heads.isEmpty()) {
            return MeasuredRadioHistoryRead()
        }
        val ordinary = heads.filter { it.physicalIdentity == null }
        val result = qualifyPurposeLeaves(source, ordinary, range, captured)
        return qualifyNativePurposes(source, heads, range, captured, result)
    }

    private suspend fun qualifyNativePurposes(
        source: HistorySource, heads: List<MeasuredHistoryCandidate>, range: HistoryRange,
        captured: MeasuredCapturedRadioHistoryReader, result: MeasuredRadioHistoryRead,
    ): MeasuredRadioHistoryRead {
        val physical = heads.mapNotNull { it.physicalIdentity }.distinct()
        if (physical.isEmpty() || HistoryOrigin.LOCAL !in range.origins) {
            return result
        }
        val wanted = heads.filter { it.physicalIdentity != null }.map(::measuredHistoryPosition).toSet()
        val purposes = range.purposes.filter { it == HistoryPurpose.CAPTURE || it == HistoryPurpose.BACKGROUND }
            .map { it.name }
        if (purposes.isEmpty()) {
            return result
        }
        val times = heads.filter { it.physicalIdentity != null }.map { it.startTimeMs }.distinct()
        return readNativePurposePages(source, physical, purposes, times, range, captured, wanted, result)
    }

    // Keep the captured frontier and original purpose inputs explicit without introducing read state.
    @Suppress("LongParameterList")
    private suspend fun readNativePurposePages(
        source: HistorySource, physical: List<String>, purposes: List<String>, times: List<Long>,
        range: HistoryRange, captured: MeasuredCapturedRadioHistoryReader,
        wanted: Set<HistoryPosition>, initial: MeasuredRadioHistoryRead,
    ): MeasuredRadioHistoryRead {
        var result = initial
        val dao = database.radioObservationChildDao()
        var afterKind: String? = null
        var afterIdentity: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val page = if (source == HistorySource.WIFI) {
                    dao.wifiNativeMeasuredPurposeCandidates(
                    physical, purposes, times, afterKind, afterIdentity, RADIO_HISTORY_CANDIDATE_QUERY_LIMIT)
                }
                else {
                    dao.cellNativeMeasuredPurposeCandidates(
                        physical, purposes, times, afterKind, afterIdentity, RADIO_HISTORY_CANDIDATE_QUERY_LIMIT)
                }
                val keys = requireNativePurposePage(page, physical, purposes, afterKind, afterIdentity)
                if (keys.isEmpty()) {
                    break
                }
                val validKeys = keys.filter { it.physicalEventId?.isNotBlank() == true }
                if (validKeys.size != keys.size) {
                    result = result.copy(problems = result.problems +
                        HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
                }
                result = qualifyNativePurposeKeys(source, validKeys, range, captured, wanted, result)
                afterKind = keys.last().kind
                afterIdentity = keys.last().identity
                if (page.size <= MAX_RADIO_HISTORY_CANDIDATES) {
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Retain the qualified prefix and its source problem on a failed page; cancellation escapes above.
                val reason = failure.measuredRadioFailureReason()
                return MeasuredRadioHistoryRead(result.rows, result.problems + HistoryProblem(source, reason))
            }
        }
        return result
    }

    private suspend fun qualifyNativePurposeKeys(
        source: HistorySource, keys: List<MeasuredHistoryCandidate>, range: HistoryRange,
        captured: MeasuredCapturedRadioHistoryReader, wanted: Set<HistoryPosition>, initial: MeasuredRadioHistoryRead,
    ): MeasuredRadioHistoryRead {
        val qualified = qualifyPurposeLeaves(source, keys, range, captured)
        check(qualified.rows.keys.all { it in wanted })
        return mergePurposeRows(initial, qualified)
    }

    private suspend fun qualifyPurposeLeaves(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange, captured: MeasuredCapturedRadioHistoryReader): MeasuredRadioHistoryRead {
        require(keys.size <= MAX_RADIO_HISTORY_CANDIDATES)
        val background = qualifyBackgroundAndLegacy(source, keys.filter { it.purpose != "CAPTURE" }, range)
        val capture = keys.filter { it.purpose == "CAPTURE" }.takeIf { it.isNotEmpty() }
            ?.let { captured.qualify(source, it, range) } ?: MeasuredRadioHistoryRead()
        return mergePurposeRows(background, capture)
    }

    /** Prefer qualified capture for session organization; background remains an independent fallback. */
    private fun mergePurposeRows(first: MeasuredRadioHistoryRead,
        next: MeasuredRadioHistoryRead): MeasuredRadioHistoryRead {
        val rows = first.rows.toMutableMap()
        next.rows.forEach { (position, row) ->
            if (rows[position]?.purpose != HistoryPurpose.CAPTURE) {
                rows[position] = row
            }
        }
        return MeasuredRadioHistoryRead(rows, first.problems + next.problems)
    }

    /** The same helper is also exercised independently by the focused background/released fixtures. */
    suspend fun qualifyBackgroundAndLegacy(source: HistorySource, candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredRadioHistoryRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(candidates.size <= MAX_RADIO_HISTORY_CANDIDATES && candidates.none { it.purpose == "CAPTURE" })
        if (source !in range.sources) {
            return MeasuredRadioHistoryRead()
        }
        val selected = candidates.filter { it.isSelectedBackgroundLeaf(range) }
        val legacy = selected.filter { it.origin == "LEGACY" }
        val background = selected.filter { it.purpose == "BACKGROUND" }
        var local = MeasuredRadioHistoryRead()
        for (group in legacy.groupBy { it.kind }.values) {
            val read = try {
                readLegacy(source, group, range)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MeasuredRadioHistoryRead(problems = setOf(HistoryProblem(source,
                    HistoryProblemReason.STORAGE_UNAVAILABLE)))
            }
            local = MeasuredRadioHistoryRead(local.rows + read.rows, local.problems + read.problems)
        }
        val groups = background.groupBy { candidate -> listOf(
            candidate.kind.removeSuffix("_summary"), candidate.origin, candidate.writerId,
            candidate.writerVersion, candidate.factId, candidate.factRevision,
        ) }.values.toList()
        val retained = readIndependentGroups(source, groups)
        return MeasuredRadioHistoryRead(local.rows + retained.rows, local.problems + retained.problems)
    }

    /** Most pages use one batched qualifier. Split only a failing batch to preserve valid siblings. */
    private suspend fun readIndependentGroups(source: HistorySource,
        groups: List<List<MeasuredHistoryCandidate>>): MeasuredRadioHistoryRead {
        if (groups.isEmpty()) {
            return MeasuredRadioHistoryRead()
        }
        var failureReason = HistoryProblemReason.INTEGRITY_UNAVAILABLE
        val result = try {
            if (source == HistorySource.WIFI) {
                readWifi(groups.flatten())
            } else {
                readCell(groups.flatten())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failureReason = HistoryProblemReason.STORAGE_UNAVAILABLE
            null
        }
        if (result != null && result.rows.keys.containsAll(groups.flatten().map(::measuredHistoryPosition))) {
            return result
        }
        if (groups.size == 1) {
            return result ?: MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(source, failureReason)))
        }
        val middle = groups.size / 2
        val first = readIndependentGroups(source, groups.take(middle))
        val second = readIndependentGroups(source, groups.drop(middle))
        return MeasuredRadioHistoryRead(first.rows + second.rows, first.problems + second.problems)
    }

    private suspend fun readWifi(keys: List<MeasuredHistoryCandidate>): MeasuredRadioHistoryRead? {
        val ids = parentIds(keys)
        val read = wifi.readSelectedInTransaction(ids.local, ids.imported, ids.localGaps, ids.importedGaps)
        val snapshot = read.result as? AmbientWifiReadResult.Snapshot ?:
            return MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(HistorySource.WIFI, if (read.result == AmbientWifiReadResult.DependencyOverflow)
                {
                HistoryProblemReason.DEPENDENCY_OVERFLOW
            } else {
                HistoryProblemReason.INTEGRITY_UNAVAILABLE
            })))
        val children = if (ids.local.isEmpty()) {
            emptyList()
        } else {
            database.radioObservationChildDao()
            .wifiCurrentBackgroundChildrenForFactIds(
                ids.local, ids.local.size * MAX_BACKGROUND_WIFI_CHILDREN_PER_FACT + 1)
        }
        val importedChildren = if (ids.imported.isEmpty()) {
            emptyList()
        } else {
            database.importedRadioChildDao()
            .wifiCurrentBackgroundChildrenForFactIds(
                ids.imported, ids.imported.size * MAX_BACKGROUND_WIFI_CHILDREN_PER_FACT + 1)
        }
        if (children.size > ids.local.size * MAX_BACKGROUND_WIFI_CHILDREN_PER_FACT ||
            importedChildren.size > ids.imported.size * MAX_BACKGROUND_WIFI_CHILDREN_PER_FACT)
            {
            return MeasuredRadioHistoryRead(problems = setOf(HistoryProblem(HistorySource.WIFI,
                HistoryProblemReason.DEPENDENCY_OVERFLOW)))
        }
        return readBackgroundMeasurements(keys, HistorySource.WIFI, read.readGaps) { key ->
            snapshot.measurement(key, children, importedChildren)
        }
    }

    private suspend fun readCell(keys: List<MeasuredHistoryCandidate>): MeasuredRadioHistoryRead? {
        val ids = parentIds(keys)
        val read = cell.readSelectedInTransaction(ids.local, ids.imported, ids.localGaps, ids.importedGaps)
        val snapshot = read.result as? AmbientCellReadResult.Snapshot ?:
            return MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(HistorySource.CELL, if (read.result == AmbientCellReadResult.DependencyOverflow)
                {
                HistoryProblemReason.DEPENDENCY_OVERFLOW
            } else {
                HistoryProblemReason.INTEGRITY_UNAVAILABLE
            })))
        val children = if (ids.local.isEmpty()) {
            emptyList()
        } else {
            database.radioObservationChildDao()
            .cellCurrentBackgroundChildrenForFactIds(
                ids.local, ids.local.size * MAX_BACKGROUND_CELL_CHILDREN_PER_FACT + 1)
        }
        val importedChildren = if (ids.imported.isEmpty()) {
            emptyList()
        } else {
            database.importedRadioChildDao()
            .cellCurrentBackgroundChildrenForFactIds(
                ids.imported, ids.imported.size * MAX_BACKGROUND_CELL_CHILDREN_PER_FACT + 1)
        }
        if (children.size > ids.local.size * MAX_BACKGROUND_CELL_CHILDREN_PER_FACT ||
            importedChildren.size > ids.imported.size * MAX_BACKGROUND_CELL_CHILDREN_PER_FACT)
            {
            return MeasuredRadioHistoryRead(problems = setOf(HistoryProblem(HistorySource.CELL,
                HistoryProblemReason.DEPENDENCY_OVERFLOW)))
        }
        return readBackgroundMeasurements(keys, HistorySource.CELL, read.readGaps) { key ->
            snapshot.measurement(key, children, importedChildren)
        }
    }

    private suspend fun readLegacy(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredRadioHistoryRead {
        if (keys.isEmpty()) {
            return MeasuredRadioHistoryRead()
        }
        val state = database.sourceEvidenceStateDao().get() ?: return MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        val ids = keys.mapNotNull { it.rowId }.distinct()
        val dao = database.radioObservationChildDao()
        val counts = if (source == HistorySource.WIFI && keys.all { it.kind == "legacy_wifi_count" })
            {
            database.legacyPreservedDataDao().wifiCountsByIds(ids)
        } else {
            emptyList()
        }
        val wifiRows = if (source == HistorySource.WIFI && counts.isEmpty() &&
            keys.none { it.kind == "legacy_wifi_count" })
            {
            dao.legacyWifiByIds(ids)
        } else {
            emptyList()
        }
        val cellRows = if (source == HistorySource.CELL) {
            dao.legacyCellByIds(ids)
        } else {
            emptyList()
        }
        return readLegacyRows(source, keys, range, state.retainedFromMs, counts, wifiRows, cellRows)
    }


    // The same bounded legacy rows and captured range/floor are used without additional queries.
    @Suppress("LongParameterList")
    private fun readLegacyRows(
        source: HistorySource, keys: List<MeasuredHistoryCandidate>, range: HistoryRange, floor: Long?,
        counts: List<LegacyLocationWifiCount>, wifiRows: List<LegacyWifiChildEntity>,
        cellRows: List<LegacyCellChildEntity>,
    ): MeasuredRadioHistoryRead {
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (key in keys) {
            if (!key.isRetainedLegacyLeaf(range, floor)) {
                continue
            }
            val measurement = legacyMeasurement(source, key, counts, wifiRows, cellRows)
            if (measurement != null) {
                rows[measuredHistoryPosition(key)] = row(key, measurement)
            }
        }
        return MeasuredRadioHistoryRead(rows)
    }

    private fun legacyMeasurement(
        source: HistorySource, key: MeasuredHistoryCandidate, counts: List<LegacyLocationWifiCount>,
        wifiRows: List<LegacyWifiChildEntity>, cellRows: List<LegacyCellChildEntity>,
    ): HistoryMeasurement? {
        return if (source == HistorySource.WIFI && key.kind == "legacy_wifi_count") {
            counts.singleOrNull {
            key.matchesLegacyRow(it.id, it.time) }
            ?.let { HistoryMeasurement.LegacyWifiCount(it.count, it.lat, it.lon, it.alt) }
        }
        else if (source == HistorySource.WIFI) {
            wifiRows.singleOrNull {
            key.matchesLegacyRow(it.legacyRowId, it.timeMs) }
            ?.let { HistoryMeasurement.LegacyWifi(it.bssid, it.ssid, it.capabilities, it.frequency, it.level) }
        }
        else {
            cellRows.singleOrNull { key.matchesLegacyRow(it.legacyRowId, it.timeMs) }
            ?.let { HistoryMeasurement.LegacyCell(it.cellId, it.lac, it.mcc, it.mnc,
                it.networkType, it.signalStrength) }
        }
    }

    private fun parentIds(keys: List<MeasuredHistoryCandidate>): SelectedBackgroundRadioParents {
        fun ids(origin: String, gap: Boolean) = keys.filter { it.origin == origin && it.kind.endsWith("_gap") == gap }
            .mapNotNull { it.factId }.distinct()
        return SelectedBackgroundRadioParents(ids("LOCAL", false), ids("IMPORTED", false),
            ids("LOCAL", true), ids("IMPORTED", true))
    }
}

internal data class MeasuredRadioHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)


private fun requireNativePurposePage(
    page: List<MeasuredHistoryCandidate>, physical: List<String>, purposes: List<String>,
    afterKind: String?, afterIdentity: String?,
): List<MeasuredHistoryCandidate> {
    check(page.size <= RADIO_HISTORY_CANDIDATE_QUERY_LIMIT && page.all {
        it.physicalIdentity in physical && it.purpose in purposes && it.origin == "LOCAL" })
    val keys = page.take(MAX_RADIO_HISTORY_CANDIDATES)
    if (keys.isNotEmpty()) {
        requireNativePurposeOrder(keys, afterKind, afterIdentity)
    }
    return keys
}

private fun requireNativePurposeOrder(
    keys: List<MeasuredHistoryCandidate>, afterKind: String?, afterIdentity: String?,
) {
    check(keys.zipWithNext().all { (a, b) -> a.kind < b.kind || a.kind == b.kind &&
        a.identity < b.identity })
    afterKind?.let { previousKind -> check(keys.first().let {
        it.kind > previousKind || it.kind == previousKind &&
            it.identity > requireNotNull(afterIdentity) }) }
}

private fun Exception.measuredRadioFailureReason(): HistoryProblemReason =
    if (this is IllegalArgumentException || this is IllegalStateException || this is ArithmeticException) {
        HistoryProblemReason.INTEGRITY_UNAVAILABLE
    } else {
        HistoryProblemReason.STORAGE_UNAVAILABLE
    }

private fun MeasuredHistoryCandidate.isSelectedBackgroundLeaf(range: HistoryRange): Boolean {
    if (range.origins.none { it.name == origin } || range.purposes.none { it.name == purpose }) {
        return false
    }
    return overlapsMeasuredRadioRange(range)
}

private fun MeasuredHistoryCandidate.overlapsMeasuredRadioRange(range: HistoryRange): Boolean {
    if (startTimeMs >= range.toExclusiveMs) {
        return false
    }
    if (startTimeMs >= range.fromInclusiveMs) {
        return true
    }
    return endTimeMs?.let { it > range.fromInclusiveMs ||
        (it == range.fromInclusiveMs && kind.endsWith("_summary")) } == true
}

private fun row(key: MeasuredHistoryCandidate, measurement: HistoryMeasurement) = UnifiedHistoryRow(
    measuredHistoryPosition(key), key.endTimeMs, setOf(HistoryOrigin.valueOf(key.origin)),
    HistoryPurpose.valueOf(key.purpose), sections = emptyList(), measurement = measurement,
)

private fun readBackgroundMeasurements(
    keys: List<MeasuredHistoryCandidate>, source: HistorySource, readGaps: Set<RadioContextReadGapReason>,
    measurement: (MeasuredHistoryCandidate) -> HistoryMeasurement?,
): MeasuredRadioHistoryRead {
    val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
    val problems = readGaps.toHistoryProblems(source).toMutableSet()
    for (key in keys) {
        val value = measurement(key)
        if (value != null) {
            rows[measuredHistoryPosition(key)] = row(key, value)
        } else if (RadioContextReadGapReason.RETENTION_BOUNDARY !in readGaps) {
            problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
    }
    return MeasuredRadioHistoryRead(rows, problems)
}

private fun AmbientWifiReadResult.Snapshot.measurement(
    key: MeasuredHistoryCandidate, children: List<AmbientWifiChildEntity>,
    importedChildren: List<ImportedAmbientWifiChildEntity>,
): HistoryMeasurement? {
    if (key.kind.endsWith("_gap")) {
        return gaps.singleOrNull { it.identity == key.factId &&
            (it.origin == AmbientWifiOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") &&
            it.startTimeMs == key.startTimeMs && it.endTimeMs == key.endTimeMs }
            ?.let { HistoryMeasurement.Gap(HistorySource.WIFI, it.reason) }
    }
    val fact = facts.singleOrNull { it.identity == key.factId &&
        it.semanticRevision == key.factRevision &&
        (it.origin == AmbientWifiOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") } ?: return null
    return fact.measurement(key, children, importedChildren)
}

private fun AmbientWifiFact.measurement(
    key: MeasuredHistoryCandidate, children: List<AmbientWifiChildEntity>,
    importedChildren: List<ImportedAmbientWifiChildEntity>,
): HistoryMeasurement? = when {
    key.kind.endsWith("_summary") && networks.isEmpty() &&
        coverageStartTimeMs == key.startTimeMs && observedTimeMs == key.endTimeMs ->
        HistoryMeasurement.BackgroundWifiSummary(this)
    key.itemIndex != null -> {
        val network = key.backgroundWifiObservation(children, importedChildren)
        network?.takeIf { it.observedTimeMs == key.startTimeMs && it in networks }
            ?.let { HistoryMeasurement.Wifi(it, coverage != AmbientWifiCoverage.COMPLETE) }
    }
    else -> null
}

private fun MeasuredHistoryCandidate.backgroundWifiObservation(
    children: List<AmbientWifiChildEntity>, importedChildren: List<ImportedAmbientWifiChildEntity>,
): WifiObservedNetwork? = if (origin == "LOCAL") {
    children.singleOrNull { it.writerId == writerId && it.writerVersion == writerVersion &&
        it.logicalFactId == factId && it.semanticRevision == factRevision &&
        it.itemIndex == itemIndex }?.toNetwork()
} else {
    importedChildren.singleOrNull { it.archiveId == writerId && it.factId == factId &&
        it.semanticRevision == factRevision && it.itemIndex == itemIndex }?.toNetwork()
}

private fun AmbientCellReadResult.Snapshot.measurement(
    key: MeasuredHistoryCandidate, children: List<AmbientCellChildEntity>,
    importedChildren: List<ImportedAmbientCellChildEntity>,
): HistoryMeasurement? {
    if (key.kind.endsWith("_gap")) {
        return gaps.singleOrNull { it.identity == key.factId &&
            (it.origin == AmbientCellOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") &&
            it.startTimeMs == key.startTimeMs && it.endTimeMs == key.endTimeMs }
            ?.let { HistoryMeasurement.Gap(HistorySource.CELL, it.reason) }
    }
    val fact = facts.singleOrNull { it.identity == key.factId &&
        it.semanticRevision == key.factRevision &&
        (it.origin == AmbientCellOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") } ?: return null
    return fact.measurement(key, children, importedChildren)
}

private fun AmbientCellFact.measurement(
    key: MeasuredHistoryCandidate, children: List<AmbientCellChildEntity>,
    importedChildren: List<ImportedAmbientCellChildEntity>,
): HistoryMeasurement? = when {
    key.kind.endsWith("_summary") && towers.isEmpty() &&
        coverageStartTimeMs == key.startTimeMs && observedTimeMs == key.endTimeMs ->
        HistoryMeasurement.BackgroundCellSummary(this)
    key.itemIndex != null -> {
        val tower = key.backgroundCellObservation(children, importedChildren)
        tower?.takeIf { it.observedTimeMs == key.startTimeMs && it in towers }
            ?.let { HistoryMeasurement.Cell(it, coverage != AmbientCellCoverage.COMPLETE) }
    }
    else -> null
}

private fun MeasuredHistoryCandidate.backgroundCellObservation(
    children: List<AmbientCellChildEntity>, importedChildren: List<ImportedAmbientCellChildEntity>,
): CellObservedTower? = if (origin == "LOCAL") {
    children.singleOrNull { it.writerId == writerId && it.writerVersion == writerVersion &&
        it.logicalFactId == factId && it.semanticRevision == factRevision &&
        it.itemIndex == itemIndex }?.toTower()
} else {
    importedChildren.singleOrNull { it.archiveId == writerId && it.factId == factId &&
        it.semanticRevision == factRevision && it.itemIndex == itemIndex }?.toTower()
}


private fun MeasuredHistoryCandidate.isRetainedLegacyLeaf(range: HistoryRange, floor: Long?): Boolean =
    purpose == "UNKNOWN" && origin == "LEGACY" &&
        startTimeMs in range.fromInclusiveMs until range.toExclusiveMs &&
        floor?.let { startTimeMs < it } != true

private fun MeasuredHistoryCandidate.matchesLegacyRow(id: Long, timeMs: Long): Boolean =
    id == rowId && timeMs == startTimeMs

private const val MAX_RADIO_HISTORY_CANDIDATES = 100
private const val RADIO_HISTORY_CANDIDATE_QUERY_LIMIT = MAX_RADIO_HISTORY_CANDIDATES + 1
private const val MAX_BACKGROUND_WIFI_CHILDREN_PER_FACT = 64
private const val MAX_BACKGROUND_CELL_CHILDREN_PER_FACT = 256

private data class SelectedBackgroundRadioParents(val local: List<String>, val imported: List<String>,
    val localGaps: List<String>, val importedGaps: List<String>)

private fun Set<RadioContextReadGapReason>.toHistoryProblems(source: HistorySource): Set<HistoryProblem> =
    filter { it != RadioContextReadGapReason.RETENTION_BOUNDARY }.map { HistoryProblem(source,
        if (it == RadioContextReadGapReason.DEPENDENCY_LIMIT) {
            HistoryProblemReason.DEPENDENCY_OVERFLOW
        }
        else {
            HistoryProblemReason.INTEGRITY_UNAVAILABLE
        }) }.toSet()

private fun AmbientWifiChildEntity.toNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun ImportedAmbientWifiChildEntity.toNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun AmbientCellChildEntity.toTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)
private fun ImportedAmbientCellChildEntity.toTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)
