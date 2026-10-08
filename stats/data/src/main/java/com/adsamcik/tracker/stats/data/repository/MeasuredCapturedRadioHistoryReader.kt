package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
import com.adsamcik.tracker.shared.base.database.data.*
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MeasuredRadioMembershipRead(val positions: Set<HistoryPosition>, val problems: Set<HistoryProblem>)

/** Original selected capture parents; the outer measured reader owns one Room snapshot and frontier. */
internal class MeasuredCapturedRadioHistoryReader(
    private val database: AppDatabase,
    private val wifi: DefaultWifiHistoryRepository,
    private val cell: DefaultCellHistoryRepository,
) {
    suspend fun qualify(source: HistorySource, candidates: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredRadioHistoryRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(candidates.size <= 100 && source in range.sources)
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = linkedSetOf<HistoryProblem>()
        val wifiImports = mutableMapOf<String, ImportedWifiProductEvaluation?>()
        val cellImports = mutableMapOf<String, ImportedCellProductEvaluation?>()
        val nativeWifiMatches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredWifiFact?>()
        val nativeCellMatches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredCellFact?>()
        for (group in candidates.groupBy { listOf(it.origin, it.writerId, it.writerVersion, it.factId, it.factRevision) }.values) {
            currentCoroutineContext().ensureActive()
            try {
                val groupRows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
                check(group.all { it.purpose == "CAPTURE" && it.origin in setOf("LOCAL", "IMPORTED") &&
                    HistoryOrigin.valueOf(it.origin) in range.origins && HistoryPurpose.CAPTURE in range.purposes })
                val key = group.first()
                val parent = requireNotNull(key.factId)
                val revision = requireNotNull(key.factRevision).also { require(it > 0L) }
                if (key.origin == "LOCAL") {
                    if (source == HistorySource.WIFI) localWifi(group, parent, revision, range, groupRows)
                    else localCell(group, parent, revision, range, groupRows)
                } else if (source == HistorySource.WIFI) {
                    val identity = requireNotNull(key.writerId)
                    val evaluation = if (identity in wifiImports) wifiImports[identity] else
                        wifi.measuredImportedEvaluationInTransaction(identity).also { wifiImports[identity] = it }
                    importedWifi(group, requireNotNull(evaluation), range, groupRows, nativeWifiMatches)
                } else {
                    val identity = requireNotNull(key.writerId)
                    val evaluation = if (identity in cellImports) cellImports[identity] else
                        cell.measuredImportedEvaluationInTransaction(identity).also { cellImports[identity] = it }
                    importedCell(group, requireNotNull(evaluation), range, groupRows, nativeCellMatches)
                }
                rows.putAll(groupRows)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MeasuredRadioParentFailure) { problems += HistoryProblem(source, failure.reason) }
            catch (_: android.database.sqlite.SQLiteException) { problems += HistoryProblem(source, HistoryProblemReason.STORAGE_UNAVAILABLE) }
            catch (_: Exception) { problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
        }
        return MeasuredRadioHistoryRead(rows, problems)
    }

    private suspend fun localWifi(keys: List<MeasuredHistoryCandidate>, id: String, revision: Long,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>) {
        val qualified = wifi.measuredFactInTransaction(id, revision) ?: return
        val fact = qualified.fact
        for (key in keys) {
            check(key.writerId == fact.writerProjectionId && key.writerVersion == fact.writerProjectionVersion)
            val measurement = if (key.kind == "captured_wifi_summary") {
                check(key.itemIndex == null && qualified.children.isEmpty() &&
                    key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion, id, revision) &&
                    key.startTimeMs == qualified.observation.intervalStartTime.raw && key.endTimeMs == qualified.observation.observedTime.raw)
                HistoryMeasurement.WifiSummary(qualified.observation)
            } else {
                check(key.kind == "captured_wifi" && key.endTimeMs == null)
                val child = requireNotNull(qualified.children.singleOrNull { it.itemIndex == key.itemIndex })
                check(key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion, id, revision, child.itemIndex) &&
                    key.startTimeMs == child.observedWallTimeMs)
                HistoryMeasurement.Wifi(child.toMeasuredNetwork(), qualified.partial)
            }
            add(key, measurement, range, output)
        }
    }

    private suspend fun localCell(keys: List<MeasuredHistoryCandidate>, id: String, revision: Long,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>) {
        val qualified = cell.measuredFactInTransaction(id, revision) ?: return
        val fact = qualified.fact
        for (key in keys) {
            check(key.writerId == fact.writerProjectionId && key.writerVersion == fact.writerProjectionVersion)
            val measurement = if (key.kind == "captured_cell_summary") {
                check(key.itemIndex == null && qualified.children.isEmpty() &&
                    key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion, id, revision) &&
                    key.startTimeMs == qualified.observation.intervalStartTime.raw && key.endTimeMs == qualified.observation.observedTime.raw)
                HistoryMeasurement.CellSummary(qualified.observation)
            } else {
                check(key.kind == "captured_cell" && key.endTimeMs == null)
                val child = requireNotNull(qualified.children.singleOrNull { it.itemIndex == key.itemIndex })
                check(key.identity == framed(fact.writerProjectionId, fact.writerProjectionVersion, id, revision, child.itemIndex) &&
                    key.startTimeMs == child.observedWallTimeMs)
                HistoryMeasurement.Cell(child.toMeasuredTower(), qualified.partial)
            }
            add(key, measurement, range, output)
        }
    }

    private suspend fun importedWifi(keys: List<MeasuredHistoryCandidate>, evaluation: ImportedWifiProductEvaluation,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>) {
        val readable = evaluation as? ImportedWifiProductEvaluation.Readable ?: throw MeasuredRadioParentFailure(
            if ((evaluation as? ImportedWifiProductEvaluation.Unverifiable)?.reason == ImportedWifiProductFailure.DEPENDENCY_OVERFLOW)
                HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        if (readable.entryDeleted) return
        val key = keys.first()
        check(key.writerId == readable.entry.identity.value && key.factRevision == readable.candidate.importRevision && key.writerVersion == null)
        val owners = readable.entry.runs.flatMap { run -> run.observations.filter { it.identity.value == key.factId }.map { run to it } }
        val (run, observation) = requireNotNull(owners.singleOrNull())
        if (run.identity in readable.deletedRunIdentities || observation.identity !in readable.retainedObservationIdentities) return
        val children = database.measuredImportedWifiChildren(readable, run.identity.value, observation)
        val partial = readable.retentionLimited || run.retentionLoss || run.captureCoverage != PortableWifiCaptureCoverage.WHOLE_RUN ||
            run.acquisitionCompleteness != PortableWifiAcquisitionCompleteness.COMPLETE || run.hasUnresolvedProviderRange ||
            observation.resultCompleteness != PortableWifiResultCompleteness.COMPLETE
        for (selected in keys) {
            val measurement = if (selected.kind == "imported_wifi_summary") {
                check(children.isEmpty() && selected.itemIndex == null &&
                    selected.identity == framed(readable.entry.identity.value, readable.candidate.importRevision, run.identity.value, observation.identity.value) &&
                    selected.startTimeMs == observation.coverageStartTimeMs && selected.endTimeMs == observation.observedTimeMs)
                HistoryMeasurement.WifiSummary(observation.toPublicObservation())
            } else {
                check(selected.kind == "imported_wifi" && selected.endTimeMs == null)
                val child = requireNotNull(children.singleOrNull { it.itemIndex == selected.itemIndex })
                check(selected.identity == framed(child.entryIdentity, child.entryImportRevision, child.runIdentity, child.observationIdentity, child.itemIndex) &&
                    selected.startTimeMs == child.observedWallTimeMs)
                HistoryMeasurement.Wifi(child.toMeasuredNetwork(), partial)
            }
            if (selected.kind != "imported_wifi" || matchingNativeWifi(readable, run.identity.value, observation.identity.value,
                    requireNotNull(children.singleOrNull { it.itemIndex == selected.itemIndex }), range, nativeMatches) == null)
                add(selected, measurement, range, output)
        }
    }

    private suspend fun importedCell(keys: List<MeasuredHistoryCandidate>, evaluation: ImportedCellProductEvaluation,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>) {
        val readable = evaluation as? ImportedCellProductEvaluation.Readable ?: throw MeasuredRadioParentFailure(
            if ((evaluation as? ImportedCellProductEvaluation.Unverifiable)?.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
                HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        if (readable.entryDeleted) return
        val key = keys.first()
        check(key.writerId == readable.entry.identity.value && key.factRevision == readable.candidate.importRevision && key.writerVersion == null)
        val owners = readable.entry.runs.flatMap { run -> run.observations.filter { it.identity.value == key.factId }.map { run to it } }
        val (run, observation) = requireNotNull(owners.singleOrNull())
        if (run.identity.value in readable.deletedRunIdentities || readable.retainedFromMs?.let { observation.coverageStartTimeMs < it } == true) return
        val children = database.measuredImportedCellChildren(readable, run.identity.value, observation)
        val partial = readable.retentionLimited || run.retentionLoss || run.captureCoverage != com.adsamcik.tracker.shared.base.database.PortableCellCaptureCoverage.WHOLE_RUN ||
            run.acquisitionCompleteness != com.adsamcik.tracker.shared.base.database.PortableCellAcquisitionCompleteness.COMPLETE ||
            observation.childCompleteness != com.adsamcik.tracker.shared.base.database.PortableCellChildCompleteness.COMPLETE
        for (selected in keys) {
            val measurement = if (selected.kind == "imported_cell_summary") {
                check(children.isEmpty() && selected.itemIndex == null &&
                    selected.identity == framed(readable.entry.identity.value, readable.candidate.importRevision, run.identity.value, observation.identity.value) &&
                    selected.startTimeMs == observation.coverageStartTimeMs && selected.endTimeMs == observation.observedTimeMs)
                HistoryMeasurement.CellSummary(observation.toPublicObservation())
            } else {
                check(selected.kind == "imported_cell" && selected.endTimeMs == null)
                val child = requireNotNull(children.singleOrNull { it.itemIndex == selected.itemIndex })
                check(selected.identity == framed(child.entryIdentity, child.entryImportRevision, child.runIdentity, child.observationIdentity, child.itemIndex) &&
                    selected.startTimeMs == child.observedWallTimeMs)
                HistoryMeasurement.Cell(child.toMeasuredTower(), partial)
            }
            if (selected.kind != "imported_cell" || matchingNativeCell(readable, run.identity.value, observation.identity.value,
                    requireNotNull(children.singleOrNull { it.itemIndex == selected.itemIndex }), range, nativeMatches) == null)
                add(selected, measurement, range, output)
        }
    }

    /** Exact aliases for a native leaf already shown instead of its identical imported copy. */
    suspend fun importedNativeMembership(source: HistorySource, entryIdentity: String,
        candidates: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredRadioMembershipRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(candidates.size <= 100)
        val selected = candidates.filter { it.origin == "LOCAL" && it.physicalIdentity != null &&
            (source == HistorySource.WIFI && it.kind in setOf("captured_wifi", "ambient_wifi") ||
                source == HistorySource.CELL && it.kind in setOf("captured_cell", "ambient_cell")) }
        if (selected.isEmpty() || HistoryOrigin.LOCAL !in range.origins) return MeasuredRadioMembershipRead(emptySet(), emptySet())
        val result = linkedSetOf<HistoryPosition>()
        val problems = linkedSetOf<HistoryProblem>()
        if (source == HistorySource.WIFI) {
            val evaluation = wifi.measuredImportedEvaluationInTransaction(entryIdentity) ?: return MeasuredRadioMembershipRead(emptySet(), emptySet())
            val readable = evaluation as? ImportedWifiProductEvaluation.Readable ?: throw MeasuredRadioParentFailure(
                if ((evaluation as? ImportedWifiProductEvaluation.Unverifiable)?.reason == ImportedWifiProductFailure.DEPENDENCY_OVERFLOW)
                    HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            if (readable.entryDeleted) return MeasuredRadioMembershipRead(emptySet(), emptySet())
            val matches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredWifiFact?>()
            for (run in readable.entry.runs) {
                if (run.identity in readable.deletedRunIdentities) continue
                for (observation in run.observations) {
                    currentCoroutineContext().ensureActive()
                    if (observation.identity !in readable.retainedObservationIdentities ||
                        selected.none { it.startTimeMs >= observation.coverageStartTimeMs &&
                            it.startTimeMs <= observation.latestPossibleTimeMs }) continue
                    try {
                        for (child in database.measuredImportedWifiChildren(readable, run.identity.value, observation)) {
                            if (selected.none { it.startTimeMs == child.observedWallTimeMs && it.itemIndex == child.itemIndex }) continue
                            val match = matchingNativeWifi(readable, run.identity.value, observation.identity.value, child, range, matches) ?: continue
                            nativeCapturePosition(source, selected, match.parent.fact.writerProjectionId,
                                match.parent.fact.writerProjectionVersion, match.parent.fact.logicalFactId,
                                match.parent.fact.semanticRevision, match.child.itemIndex)?.let { result += it }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { problems += membershipProblem(source, failure) }
                }
            }
        } else {
            val evaluation = cell.measuredImportedEvaluationInTransaction(entryIdentity) ?: return MeasuredRadioMembershipRead(emptySet(), emptySet())
            val readable = evaluation as? ImportedCellProductEvaluation.Readable ?: throw MeasuredRadioParentFailure(
                if ((evaluation as? ImportedCellProductEvaluation.Unverifiable)?.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
                    HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            if (readable.entryDeleted) return MeasuredRadioMembershipRead(emptySet(), emptySet())
            val matches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredCellFact?>()
            for (run in readable.entry.runs) {
                if (run.identity.value in readable.deletedRunIdentities) continue
                for (observation in run.observations) {
                    currentCoroutineContext().ensureActive()
                    if (readable.retainedFromMs?.let { observation.coverageStartTimeMs < it } == true ||
                        selected.none { it.startTimeMs >= observation.coverageStartTimeMs &&
                            it.startTimeMs <= observation.latestPossibleTimeMs }) continue
                    try {
                        for (child in database.measuredImportedCellChildren(readable, run.identity.value, observation)) {
                            if (selected.none { it.startTimeMs == child.observedWallTimeMs && it.itemIndex == child.itemIndex }) continue
                            val match = matchingNativeCell(readable, run.identity.value, observation.identity.value, child, range, matches) ?: continue
                            nativeCapturePosition(source, selected, match.parent.fact.writerProjectionId,
                                match.parent.fact.writerProjectionVersion, match.parent.fact.logicalFactId,
                                match.parent.fact.semanticRevision, match.child.itemIndex)?.let { result += it }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { problems += membershipProblem(source, failure) }
                }
            }
        }
        return MeasuredRadioMembershipRead(result, problems)
    }

    private fun membershipProblem(source: HistorySource, failure: Exception) = HistoryProblem(source, when (failure) {
        is MeasuredRadioParentFailure -> failure.reason
        is android.database.sqlite.SQLiteException -> HistoryProblemReason.STORAGE_UNAVAILABLE
        else -> HistoryProblemReason.INTEGRITY_UNAVAILABLE
    })

    /** Read the existing complete original tuple from SQL instead of reconstructing or decoding it. */
    private suspend fun nativeCapturePosition(source: HistorySource, selected: List<MeasuredHistoryCandidate>,
        writerId: String, writerVersion: Int, factId: String, revision: Long, itemIndex: Int): HistoryPosition? {
        val identities = selected.map { requireNotNull(it.physicalIdentity) }.distinct()
        val times = selected.map { it.startTimeMs }.distinct()
        var after: MeasuredHistoryCandidate? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = if (source == HistorySource.WIFI) database.radioObservationChildDao().wifiNativeMeasuredPurposeCandidates(
                identities, listOf("CAPTURE"), times, after?.kind, after?.identity, 101)
            else database.radioObservationChildDao().cellNativeMeasuredPurposeCandidates(
                identities, listOf("CAPTURE"), times, after?.kind, after?.identity, 101)
            val consumed = batch.take(100)
            consumed.firstOrNull { it.writerId == writerId && it.writerVersion == writerVersion &&
                it.factId == factId && it.factRevision == revision && it.itemIndex == itemIndex }
                ?.let { return measuredHistoryPosition(it) }
            if (batch.size <= 100) return null
            val next = consumed.last()
            check(after == null || next.kind > after.kind || next.kind == after.kind && next.identity > after.identity)
            after = next
        }
    }

    private data class NativeWifiMatch(val parent: QualifiedMeasuredWifiFact, val child: WifiCapturedChildEntity)
    private data class NativeCellMatch(val parent: QualifiedMeasuredCellFact, val child: CellCapturedChildEntity)

    /** A failed optional native comparison must never hide an independently valid import. */
    private suspend fun matchingNativeWifi(readable: ImportedWifiProductEvaluation.Readable, runIdentity: String,
        observationIdentity: String, child: ImportedWifiChildEntity, range: HistoryRange,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>): NativeWifiMatch? {
        if (HistoryOrigin.LOCAL !in range.origins) return null
        val logicalId = readable.collidingLocalLogicalTrackingId ?: return null
        return optionalNativeMatch {
            val candidates = database.wifiCapturedFactDao().measuredChildOriginCandidates(
                logicalId, child.observedWallTimeMs, child.itemIndex, 101)
            if (candidates.size > 100) return@optionalNativeMatch null
            val original = candidates.singleOrNull {
                PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN, it.serviceRunId).value == runIdentity &&
                PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION, it.logicalFactId).value == observationIdentity
            } ?: return@optionalNativeMatch null
            val nativeKey = original.logicalFactId to original.semanticRevision
            val qualified = (if (nativeKey in nativeMatches) nativeMatches[nativeKey] else
                wifi.measuredFactInTransaction(original.logicalFactId, original.semanticRevision).also { nativeMatches[nativeKey] = it })
                ?: return@optionalNativeMatch null
            val native = qualified.children.singleOrNull { it.itemIndex == child.itemIndex }
                ?: return@optionalNativeMatch null
            if (native.providerTimestampNanos == child.providerTimestampNanos &&
                native.observedWallTimeMs == child.observedWallTimeMs && native.wallTimeUncertaintyMs == child.wallTimeUncertaintyMs &&
                native.bssid == child.bssid && native.ssid == child.ssid && native.frequencyMhz == child.frequencyMhz &&
                native.signalDbm == child.signalDbm) NativeWifiMatch(qualified, native) else null
        }
    }

    private suspend fun matchingNativeCell(readable: ImportedCellProductEvaluation.Readable, runIdentity: String,
        observationIdentity: String, child: ImportedCellChildEntity, range: HistoryRange,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>): NativeCellMatch? {
        if (HistoryOrigin.LOCAL !in range.origins) return null
        if (readable.localOriginHandle == null) return null
        return optionalNativeMatch {
            val candidates = cell.measuredImportedOriginCandidatesInTransaction(readable, child.observedWallTimeMs, child.itemIndex)
            if (candidates.size > 100) return@optionalNativeMatch null
            val original = candidates.singleOrNull {
                com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity.derive(
                    com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind.PHYSICAL_RUN, it.serviceRunId).value == runIdentity &&
                com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity.derive(
                    com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind.OBSERVATION, it.logicalFactId).value == observationIdentity
            } ?: return@optionalNativeMatch null
            val nativeKey = original.logicalFactId to original.semanticRevision
            val qualified = (if (nativeKey in nativeMatches) nativeMatches[nativeKey] else
                cell.measuredFactInTransaction(original.logicalFactId, original.semanticRevision).also { nativeMatches[nativeKey] = it })
                ?: return@optionalNativeMatch null
            val native = qualified.children.singleOrNull { it.itemIndex == child.itemIndex }
                ?: return@optionalNativeMatch null
            if (native.providerTimestampNanos == child.providerTimestampNanos &&
                native.observedWallTimeMs == child.observedWallTimeMs && native.wallTimeUncertaintyMs == child.wallTimeUncertaintyMs &&
                native.towerIdentity == child.towerIdentity && native.radioType == child.radioType &&
                native.registered == child.registered && native.signalDbm == child.signalDbm) NativeCellMatch(qualified, native) else null
        }
    }

    private suspend fun <T> optionalNativeMatch(compare: suspend () -> T?): T? = try { compare() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun add(key: MeasuredHistoryCandidate, measurement: HistoryMeasurement, range: HistoryRange,
        output: MutableMap<HistoryPosition, UnifiedHistoryRow>) {
        val hasLastObservation = measurement is HistoryMeasurement.WifiSummary || measurement is HistoryMeasurement.CellSummary
        check(key.startTimeMs >= 0L && key.startTimeMs < range.toExclusiveMs &&
            (key.startTimeMs >= range.fromInclusiveMs || key.endTimeMs?.let {
                it > range.fromInclusiveMs || hasLastObservation && it == range.fromInclusiveMs
            } == true))
        val position = measuredHistoryPosition(key)
        output[position] = UnifiedHistoryRow(position, key.endTimeMs, setOf(HistoryOrigin.valueOf(key.origin)),
            HistoryPurpose.CAPTURE, sections = emptyList(), measurement = measurement)
    }
}

/** Internal tuple encoder mirrors SQLite discovery; it is never decoded into an action target. */
private fun framed(vararg parts: Any) = parts.joinToString(":") { it.toString().let { value -> "${value.length}:$value" } }

private fun WifiCapturedChildEntity.toMeasuredNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun ImportedWifiChildEntity.toMeasuredNetwork() = WifiObservedNetwork(observedWallTimeMs, wallTimeUncertaintyMs,
    bssid?.takeIf(String::isNotBlank), ssid?.takeIf(String::isNotEmpty), frequencyMhz, signalDbm)
private fun CellCapturedChildEntity.toMeasuredTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)
private fun ImportedCellChildEntity.toMeasuredTower() = CellObservedTower(observedWallTimeMs, wallTimeUncertaintyMs,
    towerIdentity?.takeIf(String::isNotBlank), CellHistoryTechnology.valueOf(radioType), registered, signalDbm)

/** Finite selected-parent source qualification failure; never a recording or repair instruction. */
internal class MeasuredRadioParentFailure(val reason: HistoryProblemReason) : RuntimeException(null, null, false, false)
