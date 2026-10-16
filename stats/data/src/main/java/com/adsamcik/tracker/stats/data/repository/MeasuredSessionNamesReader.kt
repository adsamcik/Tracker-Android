package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSessionId
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MeasuredSessionNamesRead(val rows: List<UnifiedHistoryRow>, val problems: Set<HistoryProblem>)

/** Metadata only, in the caller's qualified Room snapshot. It never clips, admits or duplicates data. */
internal class MeasuredSessionNamesReader(private val database: AppDatabase,
    private val capturedRadio: MeasuredCapturedRadioHistoryReader) {
    suspend fun decorate(rows: List<UnifiedHistoryRow>, candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredSessionNamesRead {
        require(rows.size <= MAX_INPUT_ROWS && candidates.size <= MAX_INPUT_ROWS)
        if (rows.isEmpty()) {
            return MeasuredSessionNamesRead(rows, emptySet())
        }
        return SessionNameLookup(rows, candidates, range).read()
    }

    private inner class SessionNameLookup(
        private val rows: List<UnifiedHistoryRow>,
        candidates: List<MeasuredHistoryCandidate>,
        private val range: HistoryRange,
    ) {
        private val keys = candidates.associateBy(::measuredHistoryPosition)
        private val names = rows.associate { it.position to linkedSetOf<String>() }
        private val selection = range.organizationSessionId
        private val included = linkedSetOf<HistoryPosition>()
        private val problems = linkedSetOf<HistoryProblem>()
        private val from = rows.minOf { minOf(it.position.startTimeMs, it.toExclusiveMs ?: it.position.startTimeMs) }
        private val last = rows.maxOf { maxOf(it.position.startTimeMs, it.toExclusiveMs ?: it.position.startTimeMs) }
        private val to = if (last == Long.MAX_VALUE) {
            last
        } else {
            last + 1L
        }

        suspend fun read(): MeasuredSessionNamesRead {
            try {
                readAnnotations()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                problems += HistoryProblem(null, HistoryProblemReason.STORAGE_UNAVAILABLE)
                rows.forEach { row ->
                    row.name?.takeIf(String::isNotBlank)?.let { names.getValue(row.position) += it }
                }
            }
            return MeasuredSessionNamesRead(rows.filter { selection == null || it.position in included }
                .map { it.copy(name = null, sessionNames = names.getValue(it.position).toList()) }, problems)
        }

        private suspend fun readAnnotations() {
            var offset = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val annotations = database.organizationAnnotationDao().visible(from, to, PAGE_SIZE, offset,
                    selectedLocalId = (selection as? OrganizationSessionId.Annotation)?.localId,
                    selectedAdapterKey = (selection as? OrganizationSessionId.Recording)?.adapterKey,
                    namedOnly = selection == null)
                for (annotation in annotations) {
                    decorateAnnotation(annotation)
                }
                if (annotations.size < PAGE_SIZE) {
                    break
                }
                offset = Math.addExact(offset, annotations.size)
            }
        }

        private suspend fun decorateAnnotation(annotation: OrganizationSessionRow) {
            val name = annotation.name?.takeIf(String::isNotBlank)
            val selected =
                try { annotation.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                problems += HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                return
            }
            if (selected != null && annotation.adapterKey != null) {
                problems += HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                return
            }
            val eligible = rows.filter { contains(annotation, it) }
            if (eligible.isEmpty()) {
                return
            }
            val locationMembers = readLocationMembers(selected, eligible)
            val nativeRadioMembers = readNativeRadioMembers(selected, eligible)
            applyName(name, selected, eligible, locationMembers, nativeRadioMembers)
        }

        private suspend fun readLocationMembers(selected: List<OrganizationSourceSelector>?,
            eligible: List<UnifiedHistoryRow>): Set<Long> {
            val locationIds =
                eligible.mapNotNull { (it.measurement as? HistoryMeasurement.Location)?.point?.sampleId }
            val locationMembers = linkedSetOf<Long>()
            if (locationIds.isNotEmpty()) {
                for (selector in selected.orEmpty().filterIsInstance<OrganizationSourceSelector.LocationFile>()) {
                    currentCoroutineContext().ensureActive()
                    locationMembers += database.locationFileIdentityDao().sampleIdsInArchive(
                        selector.archiveIdentity, locationIds
                    )
                }
            }
            return locationMembers
        }

        private suspend fun readNativeRadioMembers(selected: List<OrganizationSourceSelector>?,
            eligible: List<UnifiedHistoryRow>): Set<HistoryPosition> {
            val nativeRadioMembers = linkedSetOf<HistoryPosition>()
            for (selector in selected.orEmpty().filterIsInstance<OrganizationSourceSelector.CapturedEntry>()) {
                if (selector.source != HistorySource.WIFI && selector.source != HistorySource.CELL) {
                    continue
                }
                try {
                    val membership = capturedRadio.importedNativeMembership(selector.source, selector.entryIdentity,
                        eligible.mapNotNull { keys[it.position] }, range, selector.importJobId, selector.importEntryKey)
                    nativeRadioMembers += membership.positions
                    problems += membership.problems
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: MeasuredRadioParentFailure) {
                    problems += HistoryProblem(selector.source, failure.reason)
                }
                catch (_: android.database.sqlite.SQLiteException) {
                    problems += HistoryProblem(selector.source, HistoryProblemReason.STORAGE_UNAVAILABLE)
                } catch (_: Exception) {
                    problems += HistoryProblem(selector.source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                }
            }
            return nativeRadioMembers
        }

        private fun applyName(name: String?, selected: List<OrganizationSourceSelector>?,
            eligible: List<UnifiedHistoryRow>, locationMembers: Set<Long>, nativeRadioMembers: Set<HistoryPosition>) {
            for (row in eligible) {
                val key = keys[row.position] ?: continue
                if (selected == null || selected.any { member(it, row, key, locationMembers) ||
                    nativeRadioMember(it, row, nativeRadioMembers) }) {
                    name?.let { names.getValue(row.position) += it }
                    included += row.position
                }
            }
        }
    }

    private companion object {
        const val MAX_INPUT_ROWS = 100
        const val PAGE_SIZE = 100
    }
}

private fun contains(annotation: OrganizationSessionRow, row: UnifiedHistoryRow): Boolean {
    val from = annotation.fromMs?.takeIf { it >= 0L } ?: return false
    val to = annotation.toMs
    if (to == null && !annotation.isActive || to != null && to < from) {
        return false
    }
    val start = row.position.startTimeMs
    val end = row.toExclusiveMs
    return if (end == null || end == start) {
        pointInAnnotation(start, from, to)
    } else {
        windowOverlapsAnnotation(start, end, from, to)
    }
}

private fun pointInAnnotation(start: Long, from: Long, to: Long?): Boolean =
    if (to == from) {
        start == from
    } else {
        start >= from && (to == null || start < to)
    }

private fun windowOverlapsAnnotation(start: Long, end: Long, from: Long, to: Long?): Boolean {
    // Organization is a useful label, not recording authority or a claim that a count can be
    // divided. A crossing measured window keeps its original bounds and complete measurement.
    val first = minOf(start, end)
    val last = maxOf(start, end)
    return last > from && (to == null || first < if (to == from && to < Long.MAX_VALUE) {
        to + 1L
    } else {
        to
    })
}

private fun member(selector: OrganizationSourceSelector, row: UnifiedHistoryRow, key: MeasuredHistoryCandidate,
    locationMembers: Set<Long>): Boolean = when (selector) {
    is OrganizationSourceSelector.LocationFile ->
        (row.measurement as? HistoryMeasurement.Location)?.point?.sampleId in locationMembers
    is OrganizationSourceSelector.CapturedEntry ->
        key.matchesImported(row, "CAPTURE", selector.source, selector.entryIdentity) &&
            (selector.source != HistorySource.WIFI && selector.source != HistorySource.CELL ||
                selector.importJobId == null)
    is OrganizationSourceSelector.BackgroundStepsDay ->
        key.matchesImported(row, "BACKGROUND", HistorySource.STEPS, selector.dayIdentity)
    is OrganizationSourceSelector.BackgroundRadioArchive ->
        key.matchesImported(row, "BACKGROUND", selector.source, selector.archiveIdentity)
}

private fun MeasuredHistoryCandidate.matchesImported(row: UnifiedHistoryRow, purpose: String,
    source: HistorySource, writerId: String): Boolean = origin == "IMPORTED" && this.purpose == purpose &&
    source == row.measurement?.source && writerId == this.writerId

private fun nativeRadioMember(selector: OrganizationSourceSelector, row: UnifiedHistoryRow,
    nativeRadioMembers: Set<HistoryPosition>): Boolean = selector is OrganizationSourceSelector.CapturedEntry &&
    selector.source == row.measurement?.source && row.position in nativeRadioMembers
