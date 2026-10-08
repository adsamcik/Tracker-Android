package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.OrganizationSessionRow
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelectionCodec
import com.adsamcik.tracker.stats.api.repository.OrganizationSourceSelector
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MeasuredSessionNamesRead(val rows: List<UnifiedHistoryRow>, val problems: Set<HistoryProblem>)

/** Metadata only, in the caller's qualified Room snapshot. It never clips, admits or duplicates data. */
internal class MeasuredSessionNamesReader(private val database: AppDatabase) {
    suspend fun decorate(rows: List<UnifiedHistoryRow>, candidates: List<MeasuredHistoryCandidate>): MeasuredSessionNamesRead {
        require(rows.size <= 100 && candidates.size <= 100)
        if (rows.isEmpty()) return MeasuredSessionNamesRead(rows, emptySet())
        val keys = candidates.associateBy(::measuredHistoryPosition)
        val names = rows.associate { it.position to linkedSetOf<String>() }
        val problems = linkedSetOf<HistoryProblem>()
        val from = rows.minOf { minOf(it.position.startTimeMs, it.toExclusiveMs ?: it.position.startTimeMs) }
        val last = rows.maxOf { maxOf(it.position.startTimeMs, it.toExclusiveMs ?: it.position.startTimeMs) }
        val to = if (last == Long.MAX_VALUE) last else last + 1L
        var offset = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val annotations = database.organizationAnnotationDao().visible(from, to, PAGE_SIZE, offset, namedOnly = true)
                for (annotation in annotations) {
                    val name = annotation.name?.takeIf(String::isNotBlank) ?: continue
                    val selected = try { annotation.importedSourceSelection?.let(OrganizationSourceSelectionCodec::decode) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        problems += HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                        continue
                    }
                    if (selected != null && annotation.adapterKey != null) {
                        problems += HistoryProblem(null, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                        continue
                    }
                    val eligible = rows.filter { contains(annotation, it) }
                    if (eligible.isEmpty()) continue
                    val locationIds = eligible.mapNotNull { (it.measurement as? HistoryMeasurement.Location)?.point?.sampleId }
                    val locationMembers = linkedSetOf<Long>()
                    if (locationIds.isNotEmpty()) for (selector in selected.orEmpty().filterIsInstance<OrganizationSourceSelector.LocationFile>()) {
                        currentCoroutineContext().ensureActive()
                        locationMembers += database.locationFileIdentityDao().sampleIdsInArchive(selector.archiveIdentity, locationIds)
                    }
                    for (row in eligible) {
                        val key = keys[row.position] ?: continue
                        if (selected == null || selected.any { member(it, row, key, locationMembers) })
                            names.getValue(row.position) += name
                    }
                }
                if (annotations.size < PAGE_SIZE) break
                offset = Math.addExact(offset, annotations.size)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            problems += HistoryProblem(null, HistoryProblemReason.STORAGE_UNAVAILABLE)
            rows.forEach { row -> row.name?.takeIf(String::isNotBlank)?.let { names.getValue(row.position) += it } }
        }
        return MeasuredSessionNamesRead(rows.map { it.copy(name = null, sessionNames = names.getValue(it.position).toList()) }, problems)
    }

    private fun contains(annotation: OrganizationSessionRow, row: UnifiedHistoryRow): Boolean {
        val from = annotation.fromMs?.takeIf { it >= 0L } ?: return false
        val to = annotation.toMs
        if (to == null && !annotation.isActive || to != null && to <= from) return false
        val start = row.position.startTimeMs
        val end = row.toExclusiveMs
        if (start < from || to != null && start >= to) return false
        if (end == null) {
            val uncertainty = when (val value = row.measurement) {
                is HistoryMeasurement.Wifi -> value.network.wallTimeUncertaintyMs
                is HistoryMeasurement.Cell -> value.tower.wallTimeUncertaintyMs
                else -> 0L
            }
            return uncertainty <= start - from && (to == null || uncertainty < to - start)
        }
        if (end < start) return false
        val strictEnd = row.measurement is HistoryMeasurement.Pressure ||
            row.measurement is HistoryMeasurement.WifiSummary || row.measurement is HistoryMeasurement.CellSummary ||
            row.measurement is HistoryMeasurement.BackgroundWifiSummary || row.measurement is HistoryMeasurement.BackgroundCellSummary
        return to == null || if (strictEnd) end < to else end <= to
    }

    private fun member(selector: OrganizationSourceSelector, row: UnifiedHistoryRow, key: MeasuredHistoryCandidate,
        locationMembers: Set<Long>): Boolean = when (selector) {
        is OrganizationSourceSelector.LocationFile -> (row.measurement as? HistoryMeasurement.Location)?.point?.sampleId in locationMembers
        is OrganizationSourceSelector.CapturedEntry -> key.origin == "IMPORTED" && key.purpose == "CAPTURE" &&
            selector.source == row.measurement?.source && selector.entryIdentity == key.writerId
        is OrganizationSourceSelector.BackgroundStepsDay -> key.origin == "IMPORTED" && key.purpose == "BACKGROUND" &&
            row.measurement?.source == HistorySource.STEPS && selector.dayIdentity == key.writerId
        is OrganizationSourceSelector.BackgroundRadioArchive -> key.origin == "IMPORTED" && key.purpose == "BACKGROUND" &&
            selector.source == row.measurement?.source && selector.archiveIdentity == key.writerId
    }

    private companion object { const val PAGE_SIZE = 100 }
}
