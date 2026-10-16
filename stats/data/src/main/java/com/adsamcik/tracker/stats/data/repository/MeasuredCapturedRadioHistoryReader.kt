package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.RadioSelectedFileMember
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellChildEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedWifiChildEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.WifiCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.readRadioFileMembers
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedWifiProductEvaluation
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiObservationV1
import com.adsamcik.tracker.stats.api.repository.PortableCapturedWifiRunV1
import com.adsamcik.tracker.stats.api.repository.PortableWifiIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableWifiOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
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
    suspend fun qualify(source: HistorySource, candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredRadioHistoryRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(candidates.size <= MAX_MEASURED_CAPTURED_RADIO_CANDIDATES && source in range.sources)
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = linkedSetOf<HistoryProblem>()
        val wifiImports = mutableMapOf<String, ImportedWifiProductEvaluation?>()
        val cellImports = mutableMapOf<String, ImportedCellProductEvaluation?>()
        val nativeWifiMatches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredWifiFact?>()
        val nativeCellMatches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredCellFact?>()
        for (group in candidates.groupBy { listOf(it.origin, it.writerId, it.writerVersion, it.factId,
            it.factRevision) }.values) {
            currentCoroutineContext().ensureActive()
            try {
                rows.putAll(qualifyGroup(
                    source, group, range, wifiImports, cellImports, nativeWifiMatches, nativeCellMatches,
                ))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: MeasuredRadioParentFailure) { problems += HistoryProblem(source, failure.reason) }
            catch (_: android.database.sqlite.SQLiteException) { problems += HistoryProblem(source,
                HistoryProblemReason.STORAGE_UNAVAILABLE) }
            catch (_: Exception) { problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE) }
        }
        return MeasuredRadioHistoryRead(rows, problems)
    }

    // Keep one parent's captured source selection and the caches shared by this bounded read explicit.
    @Suppress("LongParameterList")
    private suspend fun qualifyGroup(
        source: HistorySource,
        group: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        wifiImports: MutableMap<String, ImportedWifiProductEvaluation?>,
        cellImports: MutableMap<String, ImportedCellProductEvaluation?>,
        nativeWifiMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>,
        nativeCellMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>,
    ): Map<HistoryPosition, UnifiedHistoryRow> {
        val groupRows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        check(group.all { it.purpose == "CAPTURE" && it.origin in setOf("LOCAL", "IMPORTED") &&
            HistoryOrigin.valueOf(it.origin) in range.origins && HistoryPurpose.CAPTURE in range.purposes })
        val key = group.first()
        val parent = requireNotNull(key.factId)
        val revision = requireNotNull(key.factRevision).also { require(it > 0L) }
        if (key.origin == "LOCAL") {
            if (source == HistorySource.WIFI) {
                localWifi(group, parent, revision, range, groupRows)
            }
            else {
                localCell(group, parent, revision, range, groupRows)
            }
        } else if (source == HistorySource.WIFI) {
            val identity = requireNotNull(key.writerId)
            val evaluation = wifi.cachedMeasuredWifiImport(identity, wifiImports)
            importedWifi(group, requireNotNull(evaluation), range, groupRows, nativeWifiMatches)
        } else {
            val identity = requireNotNull(key.writerId)
            val evaluation = cell.cachedMeasuredCellImport(identity, cellImports)
            importedCell(group, requireNotNull(evaluation), range, groupRows, nativeCellMatches)
        }
        return groupRows
    }

    private suspend fun localWifi(keys: List<MeasuredHistoryCandidate>, id: String, revision: Long,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>) {
        val qualified = wifi.measuredFactInTransaction(id, revision) ?: return
        for (key in keys) {
            val measurement = qualified.measuredWifiMeasurement(key, id, revision)
            addMeasuredCapturedRadioRow(key, measurement, range, output)
        }
    }

    private suspend fun localCell(keys: List<MeasuredHistoryCandidate>, id: String, revision: Long,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>) {
        val qualified = cell.measuredFactInTransaction(id, revision) ?: return
        for (key in keys) {
            val measurement = qualified.measuredCellMeasurement(key, id, revision)
            addMeasuredCapturedRadioRow(key, measurement, range, output)
        }
    }

    private suspend fun importedWifi(keys: List<MeasuredHistoryCandidate>, evaluation: ImportedWifiProductEvaluation,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>) {
        val readable = requireMeasuredWifiReadable(evaluation)
        if (readable.entryDeleted) {
            return
        }
        val key = keys.first()
        check(key.writerId == readable.entry.identity.value &&
            key.factRevision == readable.candidate.importRevision && key.writerVersion == null)
        val owners = readable.entry.runs.flatMap { run ->
            run.observations.filter { it.identity.value == key.factId }.map { run to it } }
        val (run, observation) = requireNotNull(owners.singleOrNull())
        if (run.identity in readable.deletedRunIdentities ||
            observation.identity !in readable.retainedObservationIdentities) {
            return
        }
        val children = database.measuredImportedWifiChildren(readable, run.identity.value, observation)
        val childrenByIndex = children.groupBy { it.itemIndex }
        val partial = run.hasPartialMeasuredWifiCoverage(readable, observation)
        for (selected in keys) {
            val measurement = observation.measuredImportedWifiMeasurement(
                selected, readable, run.identity.value, children, childrenByIndex, partial,
            )
            if (selected.kind != "imported_wifi" || matchingNativeWifi(readable, run.identity.value,
                observation.identity.value,
                    requireNotNull(childrenByIndex[selected.itemIndex]?.singleOrNull()),
                    range, nativeMatches) == null)
                {
                addMeasuredCapturedRadioRow(selected, measurement, range, output)
            }
        }
    }

    private suspend fun importedCell(keys: List<MeasuredHistoryCandidate>, evaluation: ImportedCellProductEvaluation,
        range: HistoryRange, output: MutableMap<HistoryPosition, UnifiedHistoryRow>,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>) {
        val readable = requireMeasuredCellReadable(evaluation)
        if (readable.entryDeleted) {
            return
        }
        val key = keys.first()
        check(key.writerId == readable.entry.identity.value &&
            key.factRevision == readable.candidate.importRevision && key.writerVersion == null)
        val owners = readable.entry.runs.flatMap { run ->
            run.observations.filter { it.identity.value == key.factId }.map { run to it } }
        val (run, observation) = requireNotNull(owners.singleOrNull())
        if (run.identity.value in readable.deletedRunIdentities ||
            observation.precedesMeasuredCellRetentionFloor(readable.retainedFromMs)) {
            return
        }
        val children = database.measuredImportedCellChildren(readable, run.identity.value, observation)
        val childrenByIndex = children.groupBy { it.itemIndex }
        val partial = run.hasPartialMeasuredCellCoverage(readable, observation)
        for (selected in keys) {
            val measurement = observation.measuredImportedCellMeasurement(
                selected, readable, run.identity.value, children, childrenByIndex, partial,
            )
            if (selected.kind != "imported_cell" || matchingNativeCell(readable, run.identity.value,
                observation.identity.value,
                    requireNotNull(childrenByIndex[selected.itemIndex]?.singleOrNull()),
                    range, nativeMatches) == null)
                {
                addMeasuredCapturedRadioRow(selected, measurement, range, output)
            }
        }
    }

    /** Exact aliases for a native leaf already shown instead of its identical imported copy. */
    suspend fun importedNativeMembership(source: HistorySource, entryIdentity: String,
        candidates: List<MeasuredHistoryCandidate>, range: HistoryRange,
        importJobId: String? = null, importEntryKey: String? = null): MeasuredRadioMembershipRead {
        require(source == HistorySource.WIFI || source == HistorySource.CELL)
        require(candidates.size <= MAX_MEASURED_CAPTURED_RADIO_CANDIDATES)
        require((importJobId == null) == (importEntryKey == null))
        val originalMembers = importJobId?.let { job ->
            val epoch = requireNotNull(database.sourceEvidenceStateDao().get()).collectedDataEpoch
            readRadioFileMembers(database, if (source == HistorySource.WIFI) {
                SourceDestinationOwnerEntity.SOURCE_WIFI
            }
                else {
                SourceDestinationOwnerEntity.SOURCE_CELL
            }, job, requireNotNull(importEntryKey), entryIdentity, epoch, MAX_IMPORTED_MEMBERS)
                .filter { it.kind == "OBSERVATION" }.associateBy { it.identity }
        }
        val selected = candidates.filter { it.isMeasuredNativeRadioLeaf(source) }
        if (candidates.isEmpty()) {
            return MeasuredRadioMembershipRead(emptySet(), emptySet())
        }
        return if (source == HistorySource.WIFI) {
            importedWifiMembership(entryIdentity, candidates, selected, range, originalMembers)
        } else {
            importedCellMembership(entryIdentity, candidates, selected, range, originalMembers)
        }
    }

    private suspend fun importedWifiMembership(
        entryIdentity: String, candidates: List<MeasuredHistoryCandidate>,
        selected: List<MeasuredHistoryCandidate>, range: HistoryRange,
        originalMembers: Map<String, RadioSelectedFileMember>?,
    ): MeasuredRadioMembershipRead {
        val result = linkedSetOf<HistoryPosition>()
        val problems = linkedSetOf<HistoryProblem>()
        val evaluation = wifi.measuredImportedEvaluationInTransaction(entryIdentity) ?:
            return MeasuredRadioMembershipRead(emptySet(), emptySet())
        val readable = requireMeasuredWifiReadable(evaluation)
        if (readable.entryDeleted) {
            return MeasuredRadioMembershipRead(emptySet(), emptySet())
        }
        val matches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredWifiFact?>()
        val selectedChildren = selected.mapTo(hashSetOf()) { it.startTimeMs to it.itemIndex }
        for (run in readable.entry.runs) {
            if (run.identity in readable.deletedRunIdentities) {
                continue
            }
            val membership = importedWifiRunMembership(
                entryIdentity, readable, run, candidates, selected, range, originalMembers, matches, selectedChildren,
            )
            result += membership.positions
            problems += membership.problems
        }
        return MeasuredRadioMembershipRead(result, problems)
    }

    // Evaluate one saved run against the captured selection and shared qualification cache.
    @Suppress("LongParameterList")
    private suspend fun importedWifiRunMembership(
        entryIdentity: String,
        readable: ImportedWifiProductEvaluation.Readable,
        run: PortableCapturedWifiRunV1,
        candidates: List<MeasuredHistoryCandidate>,
        selected: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        originalMembers: Map<String, RadioSelectedFileMember>?,
        matches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>,
        selectedChildren: Set<Pair<Long, Int?>>,
    ): MeasuredRadioMembershipRead {
        val source = HistorySource.WIFI
        val result = linkedSetOf<HistoryPosition>()
        val problems = linkedSetOf<HistoryProblem>()
        for (observation in run.observations) {
            currentCoroutineContext().ensureActive()
            if (!originalMembers.matchesMeasuredRadioFileMember(
                observation.identity.value, observation.semanticRevision, observation.contentChecksum.value,
            )) {
                continue
            }
            if (observation.identity in readable.retainedObservationIdentities) {
                result += candidates.importedRadioMembershipPositions(
                    entryIdentity, readable.candidate.importRevision, observation.identity.value,
                )
            }
            if (observation.identity !in readable.retainedObservationIdentities ||
                !selected.containsSelectedMeasuredRadioTime(
                    observation.coverageStartTimeMs, observation.latestPossibleTimeMs,
                )) {
                continue
            }
            try {
                matchedImportedWifiChildren(
                    readable, run.identity.value, observation, selectedChildren, selected, range, matches, result,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Keep other readings usable when this Wi-Fi stored membership fails validation or I/O.
                problems += measuredRadioMembershipProblem(source, failure)
            }
        }
        return MeasuredRadioMembershipRead(result, problems)
    }

    // The original selected child set, range and null-aware cache belong to one matching pass.
    @Suppress("LongParameterList")
    private suspend fun matchedImportedWifiChildren(
        readable: ImportedWifiProductEvaluation.Readable,
        runIdentity: String,
        observation: PortableCapturedWifiObservationV1,
        selectedChildren: Set<Pair<Long, Int?>>,
        selected: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        matches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>,
        result: MutableSet<HistoryPosition>,
    ) {
        val source = HistorySource.WIFI
        for (child in database.measuredImportedWifiChildren(readable,
            runIdentity, observation)) {
            if ((child.observedWallTimeMs to child.itemIndex) !in selectedChildren) {
                continue
            }
            val match = matchingNativeWifi(readable, runIdentity,
                observation.identity.value, child, range, matches) ?: continue
            database.measuredNativeRadioCapturePosition(source, selected, match.parent.fact.writerProjectionId,
                match.parent.fact.writerProjectionVersion, match.parent.fact.logicalFactId,
                match.parent.fact.semanticRevision, match.child.itemIndex)?.let { result += it }
        }
    }

    private suspend fun importedCellMembership(
        entryIdentity: String, candidates: List<MeasuredHistoryCandidate>,
        selected: List<MeasuredHistoryCandidate>, range: HistoryRange,
        originalMembers: Map<String, RadioSelectedFileMember>?,
    ): MeasuredRadioMembershipRead {
        val result = linkedSetOf<HistoryPosition>()
        val problems = linkedSetOf<HistoryProblem>()
        val evaluation = cell.measuredImportedEvaluationInTransaction(entryIdentity) ?:
            return MeasuredRadioMembershipRead(emptySet(), emptySet())
        val readable = requireMeasuredCellReadable(evaluation)
        if (readable.entryDeleted) {
            return MeasuredRadioMembershipRead(emptySet(), emptySet())
        }
        val matches = mutableMapOf<Pair<String, Long>, QualifiedMeasuredCellFact?>()
        val selectedChildren = selected.mapTo(hashSetOf()) { it.startTimeMs to it.itemIndex }
        for (run in readable.entry.runs) {
            if (run.identity.value in readable.deletedRunIdentities) {
                continue
            }
            val membership = importedCellRunMembership(
                entryIdentity, readable, run, candidates, selected, range, originalMembers, matches, selectedChildren,
            )
            result += membership.positions
            problems += membership.problems
        }
        return MeasuredRadioMembershipRead(result, problems)
    }

    // Evaluate one saved run against the captured selection and shared qualification cache.
    @Suppress("LongParameterList")
    private suspend fun importedCellRunMembership(
        entryIdentity: String,
        readable: ImportedCellProductEvaluation.Readable,
        run: PortableCapturedCellRunV1,
        candidates: List<MeasuredHistoryCandidate>,
        selected: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        originalMembers: Map<String, RadioSelectedFileMember>?,
        matches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>,
        selectedChildren: Set<Pair<Long, Int?>>,
    ): MeasuredRadioMembershipRead {
        val source = HistorySource.CELL
        val result = linkedSetOf<HistoryPosition>()
        val problems = linkedSetOf<HistoryProblem>()
        for (observation in run.observations) {
            currentCoroutineContext().ensureActive()
            if (!originalMembers.matchesMeasuredRadioFileMember(
                observation.identity.value, observation.semanticRevision, observation.contentChecksum.value,
            )) {
                continue
            }
            if (observation.precedesMeasuredCellRetentionFloor(readable.retainedFromMs)) {
                continue
            }
            result += candidates.importedRadioMembershipPositions(
                entryIdentity, readable.candidate.importRevision, observation.identity.value,
            )
            if (!selected.containsSelectedMeasuredRadioTime(
                    observation.coverageStartTimeMs, observation.latestPossibleTimeMs,
                )) {
                continue
            }
            try {
                matchedImportedCellChildren(
                    readable, run.identity.value, observation, selectedChildren, selected, range, matches, result,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
                // Keep other readings usable when this Cell stored membership fails validation or I/O.
                problems += measuredRadioMembershipProblem(source, failure)
            }
        }
        return MeasuredRadioMembershipRead(result, problems)
    }

    // The original selected child set, range and null-aware cache belong to one matching pass.
    @Suppress("LongParameterList")
    private suspend fun matchedImportedCellChildren(
        readable: ImportedCellProductEvaluation.Readable,
        runIdentity: String,
        observation: PortableCapturedCellObservationV1,
        selectedChildren: Set<Pair<Long, Int?>>,
        selected: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        matches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>,
        result: MutableSet<HistoryPosition>,
    ) {
        val source = HistorySource.CELL
        for (child in database.measuredImportedCellChildren(readable,
            runIdentity, observation)) {
            if ((child.observedWallTimeMs to child.itemIndex) !in selectedChildren) {
                continue
            }
            val match = matchingNativeCell(readable, runIdentity,
                observation.identity.value, child, range, matches) ?: continue
            database.measuredNativeRadioCapturePosition(source, selected, match.parent.fact.writerProjectionId,
                match.parent.fact.writerProjectionVersion, match.parent.fact.logicalFactId,
                match.parent.fact.semanticRevision, match.child.itemIndex)?.let { result += it }
        }
    }

    private data class NativeWifiMatch(val parent: QualifiedMeasuredWifiFact, val child: WifiCapturedChildEntity)
    private data class NativeCellMatch(val parent: QualifiedMeasuredCellFact, val child: CellCapturedChildEntity)

    /** A failed optional native comparison must never hide an independently valid import. */
    private suspend fun matchingNativeWifi(readable: ImportedWifiProductEvaluation.Readable, runIdentity: String,
        observationIdentity: String, child: ImportedWifiChildEntity, range: HistoryRange,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredWifiFact?>): NativeWifiMatch? {
        if (HistoryOrigin.LOCAL !in range.origins) {
            return null
        }
        val logicalId = readable.collidingLocalLogicalTrackingId ?: return null
        return optionalMeasuredNativeMatch {
            val candidates = database.wifiCapturedFactDao().measuredChildOriginCandidates(
                logicalId, child.observedWallTimeMs, child.itemIndex, MAX_MEASURED_CAPTURED_RADIO_CANDIDATES + 1)
            if (candidates.size > MAX_MEASURED_CAPTURED_RADIO_CANDIDATES) {
                return@optionalMeasuredNativeMatch null
            }
            val original = candidates.singleOrNull {
                PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.PHYSICAL_RUN,
                    it.serviceRunId).value == runIdentity &&
                PortableWifiOpaqueIdentity.derive(PortableWifiIdentityKind.OBSERVATION,
                    it.logicalFactId).value == observationIdentity
            } ?: return@optionalMeasuredNativeMatch null
            val qualified = wifi.cachedMeasuredWifiFact(
                original.logicalFactId, original.semanticRevision, nativeMatches,
            )
                ?: return@optionalMeasuredNativeMatch null
            val native = qualified.children.singleOrNull { it.itemIndex == child.itemIndex }
                ?: return@optionalMeasuredNativeMatch null
            if (native.hasSameMeasuredValues(child)) {
                NativeWifiMatch(qualified, native)
            } else {
                null
            }
        }
    }

    private suspend fun matchingNativeCell(readable: ImportedCellProductEvaluation.Readable, runIdentity: String,
        observationIdentity: String, child: ImportedCellChildEntity, range: HistoryRange,
        nativeMatches: MutableMap<Pair<String, Long>, QualifiedMeasuredCellFact?>): NativeCellMatch? {
        if (HistoryOrigin.LOCAL !in range.origins) {
            return null
        }
        if (readable.localOriginHandle == null) {
            return null
        }
        return optionalMeasuredNativeMatch {
            val candidates = cell.measuredImportedOriginCandidatesInTransaction(readable,
                child.observedWallTimeMs, child.itemIndex)
            if (candidates.size > MAX_MEASURED_CAPTURED_RADIO_CANDIDATES) {
                return@optionalMeasuredNativeMatch null
            }
            val original = candidates.singleOrNull {
                PortableCellOpaqueIdentity.derive(
                    PortableCellIdentityKind.PHYSICAL_RUN, it.serviceRunId).value == runIdentity &&
                PortableCellOpaqueIdentity.derive(
                    PortableCellIdentityKind.OBSERVATION, it.logicalFactId).value == observationIdentity
            } ?: return@optionalMeasuredNativeMatch null
            val qualified = cell.cachedMeasuredCellFact(
                original.logicalFactId, original.semanticRevision, nativeMatches,
            )
                ?: return@optionalMeasuredNativeMatch null
            val native = qualified.children.singleOrNull { it.itemIndex == child.itemIndex }
                ?: return@optionalMeasuredNativeMatch null
            if (native.hasSameMeasuredValues(child)) {
                NativeCellMatch(qualified, native)
            } else {
                null
            }
        }
    }

}

private fun PortableCapturedCellObservationV1.precedesMeasuredCellRetentionFloor(retainedFromMs: Long?): Boolean =
    retainedFromMs?.let { coverageStartTimeMs < it } == true

private const val MAX_IMPORTED_MEMBERS = 20_000

/** Finite selected-parent source qualification failure; never a recording or repair instruction. */
internal class MeasuredRadioParentFailure(val reason: HistoryProblemReason) : RuntimeException(null, null, false, false)
