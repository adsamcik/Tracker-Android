package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.AmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedAmbientWifiChildEntity
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
    suspend fun candidates(source: HistorySource, range: HistoryRange, after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        if (source !in range.sources) return emptyList()
        val dao = database.radioObservationChildDao()
        val origins = range.origins.map { it.name }
        val purposes = range.purposes.map { it.name }
        if (origins.isEmpty() || purposes.isEmpty()) return emptyList()
        val radio = if (source == HistorySource.WIFI) dao.wifiMeasuredHistoryCandidates(
            range.fromInclusiveMs, range.toExclusiveMs, origins, purposes,
            after?.startTimeMs, after?.originTag, after?.stableId, 101,
        ) else dao.cellMeasuredHistoryCandidates(
            range.fromInclusiveMs, range.toExclusiveMs, origins, purposes,
            after?.startTimeMs, after?.originTag, after?.stableId, 101,
        )
        if (source != HistorySource.WIFI || HistoryOrigin.LEGACY !in range.origins ||
            HistoryPurpose.UNKNOWN !in range.purposes) return radio
        val counts = database.legacyPreservedDataDao().wifiCountHistoryCandidates(
            range.fromInclusiveMs, range.toExclusiveMs, after?.startTimeMs, after?.originTag, after?.stableId, 101)
        // Each source-local stream is bounded before the same strict global frontier is selected.
        return (radio + counts).sortedBy(::measuredHistoryPosition).take(101)
    }

    /** One physical frontier; each selected purpose still supplies its own original authority. */
    suspend fun qualify(source: HistorySource, heads: List<MeasuredHistoryCandidate>, range: HistoryRange,
        captured: MeasuredCapturedRadioHistoryReader): MeasuredRadioHistoryRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(heads.size <= 100)
        if (source !in range.sources || heads.isEmpty()) return MeasuredRadioHistoryRead()
        val ordinary = heads.filter { it.physicalIdentity == null }
        var result = qualifyPurposeLeaves(source, ordinary, range, captured)
        val physical = heads.mapNotNull { it.physicalIdentity }.distinct()
        if (physical.isEmpty() || HistoryOrigin.LOCAL !in range.origins) return result
        val wanted = heads.filter { it.physicalIdentity != null }.map(::measuredHistoryPosition).toSet()
        val purposes = range.purposes.filter { it == HistoryPurpose.CAPTURE || it == HistoryPurpose.BACKGROUND }
            .map { it.name }
        if (purposes.isEmpty()) return result
        val times = heads.filter { it.physicalIdentity != null }.map { it.startTimeMs }.distinct()
        val dao = database.radioObservationChildDao()
        var afterKind: String? = null
        var afterIdentity: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val page = if (source == HistorySource.WIFI) dao.wifiNativeMeasuredPurposeCandidates(
                    physical, purposes, times, afterKind, afterIdentity, 101)
                else dao.cellNativeMeasuredPurposeCandidates(physical, purposes, times, afterKind, afterIdentity, 101)
                check(page.size <= 101 && page.all { it.physicalIdentity in physical && it.purpose in purposes &&
                    it.origin == "LOCAL" })
                val keys = page.take(100)
                if (keys.isEmpty()) break
                check(keys.zipWithNext().all { (a, b) -> a.kind < b.kind || a.kind == b.kind && a.identity < b.identity })
                afterKind?.let { previousKind -> check(keys.first().let {
                    it.kind > previousKind || it.kind == previousKind && it.identity > requireNotNull(afterIdentity) }) }
                val validKeys = keys.filter { it.physicalEventId?.isNotBlank() == true }
                if (validKeys.size != keys.size) result = result.copy(problems = result.problems +
                    HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE))
                val qualified = qualifyPurposeLeaves(source, validKeys, range, captured)
                check(qualified.rows.keys.all { it in wanted })
                result = mergePurposeRows(result, qualified)
                afterKind = keys.last().kind
                afterIdentity = keys.last().identity
                if (page.size <= 100) break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val reason = if (failure is IllegalArgumentException || failure is IllegalStateException ||
                    failure is ArithmeticException) HistoryProblemReason.INTEGRITY_UNAVAILABLE
                    else HistoryProblemReason.STORAGE_UNAVAILABLE
                return MeasuredRadioHistoryRead(result.rows, result.problems + HistoryProblem(source, reason))
            }
        }
        return result
    }

    private suspend fun qualifyPurposeLeaves(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange, captured: MeasuredCapturedRadioHistoryReader): MeasuredRadioHistoryRead {
        require(keys.size <= 100)
        val background = qualifyBackgroundAndLegacy(source, keys.filter { it.purpose != "CAPTURE" }, range)
        val capture = keys.filter { it.purpose == "CAPTURE" }.takeIf { it.isNotEmpty() }
            ?.let { captured.qualify(source, it, range) } ?: MeasuredRadioHistoryRead()
        return mergePurposeRows(background, capture)
    }

    /** Prefer qualified capture for session organization; background remains an independent fallback. */
    private fun mergePurposeRows(first: MeasuredRadioHistoryRead, next: MeasuredRadioHistoryRead): MeasuredRadioHistoryRead {
        val rows = first.rows.toMutableMap()
        next.rows.forEach { (position, row) ->
            if (rows[position]?.purpose != HistoryPurpose.CAPTURE) rows[position] = row
        }
        return MeasuredRadioHistoryRead(rows, first.problems + next.problems)
    }

    /** The same helper is also exercised independently by the focused background/released fixtures. */
    suspend fun qualifyBackgroundAndLegacy(source: HistorySource, candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredRadioHistoryRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(candidates.size <= 100 && candidates.none { it.purpose == "CAPTURE" })
        if (source !in range.sources) return MeasuredRadioHistoryRead()
        val selected = candidates.filter { key ->
            range.origins.any { it.name == key.origin } && range.purposes.any { it.name == key.purpose } &&
                key.startTimeMs < range.toExclusiveMs && (key.startTimeMs >= range.fromInclusiveMs ||
                key.endTimeMs?.let { it > range.fromInclusiveMs ||
                    (it == range.fromInclusiveMs && key.kind.endsWith("_summary")) } == true)
        }
        val legacy = selected.filter { it.origin == "LEGACY" }
        val background = selected.filter { it.purpose == "BACKGROUND" }
        var local = MeasuredRadioHistoryRead()
        for (group in legacy.groupBy { it.kind }.values) {
            val read = try {
                readLegacy(source, group, range)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                MeasuredRadioHistoryRead(problems = setOf(HistoryProblem(source, HistoryProblemReason.STORAGE_UNAVAILABLE)))
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
        if (groups.isEmpty()) return MeasuredRadioHistoryRead()
        var failureReason = HistoryProblemReason.INTEGRITY_UNAVAILABLE
        val result = try {
            if (source == HistorySource.WIFI) readWifi(groups.flatten()) else readCell(groups.flatten())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failureReason = HistoryProblemReason.STORAGE_UNAVAILABLE
            null
        }
        if (result != null && result.rows.keys.containsAll(groups.flatten().map(::measuredHistoryPosition))) return result
        if (groups.size == 1) return result ?: MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(source, failureReason)))
        val middle = groups.size / 2
        val first = readIndependentGroups(source, groups.take(middle))
        val second = readIndependentGroups(source, groups.drop(middle))
        return MeasuredRadioHistoryRead(first.rows + second.rows, first.problems + second.problems)
    }

    private suspend fun readWifi(keys: List<MeasuredHistoryCandidate>): MeasuredRadioHistoryRead? {
        val ids = parentIds(keys)
        val read = wifi.readSelectedInTransaction(ids.local, ids.imported, ids.localGaps, ids.importedGaps)
        val snapshot = read.result as? AmbientWifiReadResult.Snapshot ?: return MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(HistorySource.WIFI, if (read.result == AmbientWifiReadResult.DependencyOverflow)
                HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        val children = if (ids.local.isEmpty()) emptyList() else database.radioObservationChildDao()
            .wifiCurrentBackgroundChildrenForFactIds(ids.local, ids.local.size * 64 + 1)
        val importedChildren = if (ids.imported.isEmpty()) emptyList() else database.importedRadioChildDao()
            .wifiCurrentBackgroundChildrenForFactIds(ids.imported, ids.imported.size * 64 + 1)
        if (children.size > ids.local.size * 64 || importedChildren.size > ids.imported.size * 64)
            return MeasuredRadioHistoryRead(problems = setOf(HistoryProblem(HistorySource.WIFI, HistoryProblemReason.DEPENDENCY_OVERFLOW)))
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = read.readGaps.toHistoryProblems(HistorySource.WIFI).toMutableSet()
        for (key in keys) {
            val measurement = if (key.kind.endsWith("_gap")) {
                snapshot.gaps.singleOrNull { it.identity == key.factId &&
                    (it.origin == AmbientWifiOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") &&
                    it.startTimeMs == key.startTimeMs && it.endTimeMs == key.endTimeMs }
                    ?.let { HistoryMeasurement.Gap(HistorySource.WIFI, it.reason) }
            } else {
                val fact = snapshot.facts.singleOrNull { it.identity == key.factId &&
                    it.semanticRevision == key.factRevision &&
                    (it.origin == AmbientWifiOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") }
                when {
                    fact == null -> null
                    key.kind.endsWith("_summary") && fact.networks.isEmpty() &&
                        fact.coverageStartTimeMs == key.startTimeMs && fact.observedTimeMs == key.endTimeMs ->
                        HistoryMeasurement.BackgroundWifiSummary(fact)
                    key.itemIndex != null -> {
                        val network = if (key.origin == "LOCAL") children.singleOrNull {
                            it.writerId == key.writerId && it.writerVersion == key.writerVersion &&
                                it.logicalFactId == key.factId && it.semanticRevision == key.factRevision &&
                                it.itemIndex == key.itemIndex }?.toNetwork()
                        else importedChildren.singleOrNull { it.archiveId == key.writerId &&
                            it.factId == key.factId && it.semanticRevision == key.factRevision &&
                            it.itemIndex == key.itemIndex }?.toNetwork()
                        network?.takeIf { it.observedTimeMs == key.startTimeMs && it in fact.networks }
                            ?.let { HistoryMeasurement.Wifi(it, fact.coverage != AmbientWifiCoverage.COMPLETE) }
                    }
                    else -> null
                }
            }
            if (measurement != null) rows[measuredHistoryPosition(key)] = row(key, measurement)
            else if (RadioContextReadGapReason.RETENTION_BOUNDARY !in read.readGaps)
                problems += HistoryProblem(HistorySource.WIFI, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        return MeasuredRadioHistoryRead(rows, problems)
    }

    private suspend fun readCell(keys: List<MeasuredHistoryCandidate>): MeasuredRadioHistoryRead? {
        val ids = parentIds(keys)
        val read = cell.readSelectedInTransaction(ids.local, ids.imported, ids.localGaps, ids.importedGaps)
        val snapshot = read.result as? AmbientCellReadResult.Snapshot ?: return MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(HistorySource.CELL, if (read.result == AmbientCellReadResult.DependencyOverflow)
                HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        val children = if (ids.local.isEmpty()) emptyList() else database.radioObservationChildDao()
            .cellCurrentBackgroundChildrenForFactIds(ids.local, ids.local.size * 256 + 1)
        val importedChildren = if (ids.imported.isEmpty()) emptyList() else database.importedRadioChildDao()
            .cellCurrentBackgroundChildrenForFactIds(ids.imported, ids.imported.size * 256 + 1)
        if (children.size > ids.local.size * 256 || importedChildren.size > ids.imported.size * 256)
            return MeasuredRadioHistoryRead(problems = setOf(HistoryProblem(HistorySource.CELL, HistoryProblemReason.DEPENDENCY_OVERFLOW)))
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = read.readGaps.toHistoryProblems(HistorySource.CELL).toMutableSet()
        for (key in keys) {
            val measurement = if (key.kind.endsWith("_gap")) {
                snapshot.gaps.singleOrNull { it.identity == key.factId &&
                    (it.origin == AmbientCellOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") &&
                    it.startTimeMs == key.startTimeMs && it.endTimeMs == key.endTimeMs }
                    ?.let { HistoryMeasurement.Gap(HistorySource.CELL, it.reason) }
            } else {
                val fact = snapshot.facts.singleOrNull { it.identity == key.factId &&
                    it.semanticRevision == key.factRevision &&
                    (it.origin == AmbientCellOrigin.LOCAL_DEVICE) == (key.origin == "LOCAL") }
                when {
                    fact == null -> null
                    key.kind.endsWith("_summary") && fact.towers.isEmpty() &&
                        fact.coverageStartTimeMs == key.startTimeMs && fact.observedTimeMs == key.endTimeMs ->
                        HistoryMeasurement.BackgroundCellSummary(fact)
                    key.itemIndex != null -> {
                        val tower = if (key.origin == "LOCAL") children.singleOrNull {
                            it.writerId == key.writerId && it.writerVersion == key.writerVersion &&
                                it.logicalFactId == key.factId && it.semanticRevision == key.factRevision &&
                                it.itemIndex == key.itemIndex }?.toTower()
                        else importedChildren.singleOrNull { it.archiveId == key.writerId &&
                            it.factId == key.factId && it.semanticRevision == key.factRevision &&
                            it.itemIndex == key.itemIndex }?.toTower()
                        tower?.takeIf { it.observedTimeMs == key.startTimeMs && it in fact.towers }
                            ?.let { HistoryMeasurement.Cell(it, fact.coverage != AmbientCellCoverage.COMPLETE) }
                    }
                    else -> null
                }
            }
            if (measurement != null) rows[measuredHistoryPosition(key)] = row(key, measurement)
            else if (RadioContextReadGapReason.RETENTION_BOUNDARY !in read.readGaps)
                problems += HistoryProblem(HistorySource.CELL, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        return MeasuredRadioHistoryRead(rows, problems)
    }

    private suspend fun readLegacy(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredRadioHistoryRead {
        if (keys.isEmpty()) return MeasuredRadioHistoryRead()
        val state = database.sourceEvidenceStateDao().get() ?: return MeasuredRadioHistoryRead(problems = setOf(
            HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        val ids = keys.mapNotNull { it.rowId }.distinct()
        val dao = database.radioObservationChildDao()
        val counts = if (source == HistorySource.WIFI && keys.all { it.kind == "legacy_wifi_count" })
            database.legacyPreservedDataDao().wifiCountsByIds(ids) else emptyList()
        val wifiRows = if (source == HistorySource.WIFI && counts.isEmpty() && keys.none { it.kind == "legacy_wifi_count" })
            dao.legacyWifiByIds(ids) else emptyList()
        val cellRows = if (source == HistorySource.CELL) dao.legacyCellByIds(ids) else emptyList()
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (key in keys) {
            if (key.purpose != "UNKNOWN" || key.origin != "LEGACY" ||
                key.startTimeMs !in range.fromInclusiveMs until range.toExclusiveMs ||
                state.retainedFromMs?.let { key.startTimeMs < it } == true) continue
            val measurement = if (source == HistorySource.WIFI && key.kind == "legacy_wifi_count") counts.singleOrNull {
                it.id == key.rowId && it.time == key.startTimeMs }
                ?.let { HistoryMeasurement.LegacyWifiCount(it.count, it.lat, it.lon, it.alt) }
            else if (source == HistorySource.WIFI) wifiRows.singleOrNull {
                it.legacyRowId == key.rowId && it.timeMs == key.startTimeMs }
                ?.let { HistoryMeasurement.LegacyWifi(it.bssid, it.ssid, it.capabilities, it.frequency, it.level) }
            else cellRows.singleOrNull { it.legacyRowId == key.rowId && it.timeMs == key.startTimeMs }
                ?.let { HistoryMeasurement.LegacyCell(it.cellId, it.lac, it.mcc, it.mnc, it.networkType, it.signalStrength) }
            if (measurement != null) rows[measuredHistoryPosition(key)] = row(key, measurement)
        }
        return MeasuredRadioHistoryRead(rows)
    }

    private fun row(key: MeasuredHistoryCandidate, measurement: HistoryMeasurement) = UnifiedHistoryRow(
        measuredHistoryPosition(key), key.endTimeMs, setOf(HistoryOrigin.valueOf(key.origin)),
        HistoryPurpose.valueOf(key.purpose), sections = emptyList(), measurement = measurement,
    )

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

private data class SelectedBackgroundRadioParents(val local: List<String>, val imported: List<String>,
    val localGaps: List<String>, val importedGaps: List<String>)

private fun Set<RadioContextReadGapReason>.toHistoryProblems(source: HistorySource): Set<HistoryProblem> =
    filter { it != RadioContextReadGapReason.RETENTION_BOUNDARY }.map { HistoryProblem(source,
        if (it == RadioContextReadGapReason.DEPENDENCY_LIMIT) HistoryProblemReason.DEPENDENCY_OVERFLOW
        else HistoryProblemReason.INTEGRITY_UNAVAILABLE) }.toSet()

private fun AmbientWifiChildEntity.toNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun ImportedAmbientWifiChildEntity.toNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun AmbientCellChildEntity.toTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)
private fun ImportedAmbientCellChildEntity.toTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)
