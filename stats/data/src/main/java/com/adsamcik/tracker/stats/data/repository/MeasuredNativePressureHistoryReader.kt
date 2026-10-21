package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException

/** Original native Pressure windows, qualified by the existing source selector. */
internal class MeasuredNativePressureHistoryReader(
    private val database: AppDatabase,
    private val pressure: PressureHistorySelector,
) {
    suspend fun candidates(range: HistoryRange, after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        if (HistorySource.PRESSURE !in range.sources || HistoryOrigin.LOCAL !in range.origins ||
            HistoryPurpose.CAPTURE !in range.purposes) {
            return emptyList()
        }
        val nativeFallback = if (HistoryPurpose.BACKGROUND in range.purposes) { 0 } else { 1 }
        val includeImported = if (HistoryOrigin.IMPORTED in range.origins) { 1 } else { 0 }
        val seek = if (after == null) {
            ""
        } else {
            """AND (startTimeMs < ? OR (startTimeMs = ? AND
            ('local:pressure' > ? OR ('local:pressure' = ? AND identity > ?))))"""
        }
        val args = mutableListOf<Any?>(range.toExclusiveMs, range.fromInclusiveMs)
        if (after != null) {
            args.addAll(listOf(after.startTimeMs, after.startTimeMs, after.originTag, after.originTag, after.stableId))
        }
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery("""
            SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId,
                writerId, writerVersion, factId, factRevision, itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity FROM (
            SELECT 'pressure' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
                'pressure:' || length(f.writer_projection_id) || ':' || f.writer_projection_id || ':' ||
                f.writer_projection_version || ':' || length(f.logical_fact_id) || ':' || f.logical_fact_id || ':' ||
                f.semantic_revision AS identity,
                f.interval_start_time_ms AS startTimeMs, f.interval_end_time_ms AS endTimeMs,
                r.session_segment_id AS rowId, f.writer_projection_id AS writerId,
                f.writer_projection_version AS writerVersion, f.logical_fact_id AS factId,
                f.semantic_revision AS factRevision, NULL AS itemIndex
            FROM pressure_fact_revision f LEFT JOIN source_service_run r ON r.service_run_id = f.service_run_id
            WHERE f.interval_start_time_ms >= 0 AND f.interval_start_time_ms < ? AND f.interval_end_time_ms >= ?
                AND ($nativeFallback = 1 OR NOT EXISTS (SELECT 1 FROM pressure_sample p
                    WHERE p.source_signal_id = 'all-day:pressure:' || f.source_event_id
                        AND p.time_ms = f.interval_end_time_ms AND p.pressure_hpa > 0
                        AND p.pressure_hpa <= 3.4028235e38
                        AND p.sample_count = f.sample_count
                        AND p.window_start_elapsed_realtime_nanos = f.window_start_elapsed_realtime_nanos
                        AND p.window_end_elapsed_realtime_nanos = f.window_end_elapsed_realtime_nanos
                        AND ($includeImported = 1 OR
                            instr(',' || COALESCE(p.capability_flags, '') || ',', ',PORTABLE_IMPORT,') = 0)))
                AND NOT EXISTS (SELECT 1 FROM pressure_fact_revision newer
                    WHERE newer.writer_projection_id = f.writer_projection_id AND
                        newer.writer_projection_version = f.writer_projection_version AND
                        newer.logical_fact_id = f.logical_fact_id AND newer.semantic_revision > f.semantic_revision))
            WHERE 1 $seek ORDER BY startTimeMs DESC, identity LIMIT 101
            """, args.toTypedArray()))
    }

    suspend fun qualify(keys: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredNativePressureHistoryRead {
        requireNativeCapturedCandidates(keys, "pressure", MAX_MEASURED_CANDIDATES)
        if (keys.isEmpty() || HistorySource.PRESSURE !in range.sources || HistoryOrigin.LOCAL !in range.origins ||
            HistoryPurpose.CAPTURE !in range.purposes) {
            return MeasuredNativePressureHistoryRead()
        }
        val selected = keys.groupBy { PressureMeasuredFactKey(requireNotNull(it.writerId),
            requireNotNull(it.writerVersion),
            requireNotNull(it.factId), requireNotNull(it.factRevision)) }
        val read = readIndependent(selected.keys.toList())
        val names = MeasuredNativeSessionNames.load(database,
            read.histories.mapNotNull { it.segment.logicalTrackingId })
        return qualifySelectedRows(selected, read, range, names)
    }

    private fun qualifySelectedRows(
        selected: Map<PressureMeasuredFactKey, List<MeasuredHistoryCandidate>>,
        read: PressureMeasuredRead,
        range: HistoryRange,
        names: MeasuredNativeSessionNames,
    ): MeasuredNativePressureHistoryRead {
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = read.problems.toMutableSet()
        for ((identity, candidates) in selected) {
            val owned = read.histories.flatMap { history -> history.windows.map { history to it } }
                .singleOrNull { (_, window) -> window.matchesKey(identity) }
            if (owned == null) {
                continue
            }
            val (history, window) = owned
            if (!history.hasQualifiedRetainedProof || window.semanticRevision != identity.revision) {
                problems += HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                continue
            }
            rows.putAll(measuredWindowRows(history, window, candidates, range, names, problems))
        }
        return MeasuredNativePressureHistoryRead(rows, problems)
    }

    private fun PressureHistoryWindow.matchesKey(key: PressureMeasuredFactKey): Boolean =
        writerProjectionId == key.writerId && writerProjectionVersion == key.writerVersion &&
            logicalFactId == key.factId

    private fun measuredWindowRows(
        history: PressurePhysicalHistory,
        window: PressureHistoryWindow,
        candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        names: MeasuredNativeSessionNames,
        problems: MutableSet<HistoryProblem>,
    ): Map<HistoryPosition, UnifiedHistoryRow> {
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (key in candidates) {
            if (hasInvalidWindowPosition(key, history.segment.id, window, range)) {
                problems += HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                continue
            }
            val position = measuredHistoryPosition(key)
            rows[position] = UnifiedHistoryRow(position, window.intervalEndTimeMs, setOf(HistoryOrigin.LOCAL),
                HistoryPurpose.CAPTURE, name = names.window(window.logicalTrackingId, window.intervalStartTimeMs,
                    window.intervalEndTimeMs), sections = emptyList(),
                measurement = HistoryMeasurement.Pressure(window.toPublicWindow()))
        }
        return rows
    }

    private fun hasInvalidWindowPosition(
        key: MeasuredHistoryCandidate,
        segmentId: Long,
        window: PressureHistoryWindow,
        range: HistoryRange,
    ): Boolean = key.rowId != segmentId || key.startTimeMs != window.intervalStartTimeMs ||
        key.endTimeMs != window.intervalEndTimeMs || window.intervalStartTimeMs >= range.toExclusiveMs ||
        window.intervalEndTimeMs < range.fromInclusiveMs

    private suspend fun readIndependent(keys: List<PressureMeasuredFactKey>): PressureMeasuredRead {
        if (keys.isEmpty()) {
            return PressureMeasuredRead()
        }
        val result = readSelected(keys)
        val qualified = result.histories.filter { it.hasQualifiedRetainedProof }.flatMap { it.windows }
        if (keys.size == 1 || keys.all { key -> qualified.any { it.matchesKey(key) } }) {
            return result
        }
        val middle = keys.size / 2
        val left = readIndependent(keys.take(middle))
        val right = readIndependent(keys.drop(middle))
        return PressureMeasuredRead(left.histories + right.histories, left.problems + right.problems)
    }

    private suspend fun readSelected(keys: List<PressureMeasuredFactKey>): PressureMeasuredRead =
        try {
            val histories = pressure.selectMeasuredWindowsInTransaction(keys)
            val qualifiedIds =
                histories.filter { it.hasQualifiedRetainedProof }.flatMap { it.windows }
                    .map { it.logicalFactId }.toSet()
            val problems = histories.filter { !it.hasQualifiedRetainedProof }.flatMap { it.reasons }
                .filter { it !in setOf(PressureHistoryReason.OUTSIDE_RETAINED_FLOOR,
                    PressureHistoryReason.DELETED_FACTS,
                    PressureHistoryReason.SOURCE_NOT_CAPTURED, PressureHistoryReason.STALE_COLLECTED_DATA_EPOCH) }
                .mapTo(linkedSetOf()) { HistoryProblem(HistorySource.PRESSURE,
                    if (it == PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW) {
                        HistoryProblemReason.DEPENDENCY_OVERFLOW
                    }
                    else {
                        HistoryProblemReason.INTEGRITY_UNAVAILABLE
                    }) }
            if (histories.isEmpty() || keys.any { it.factId !in qualifiedIds } && problems.isEmpty() &&
                histories.none { it.reasons.any { cause -> cause in setOf(PressureHistoryReason.OUTSIDE_RETAINED_FLOOR,
                    PressureHistoryReason.DELETED_FACTS, PressureHistoryReason.STALE_COLLECTED_DATA_EPOCH) } })
                {
                problems += HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            }
            PressureMeasuredRead(histories, problems)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: PressureHistoryPageDependencyOverflow) { PressureMeasuredRead(problems = setOf(
            HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.DEPENDENCY_OVERFLOW))) }
        catch (_: android.database.sqlite.SQLiteException) { PressureMeasuredRead(problems = setOf(
            HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.STORAGE_UNAVAILABLE))) }
        catch (_: Exception) { PressureMeasuredRead(problems = setOf(
            HistoryProblem(HistorySource.PRESSURE, HistoryProblemReason.INTEGRITY_UNAVAILABLE))) }
}

private data class PressureMeasuredRead(
    val histories: List<PressurePhysicalHistory> = emptyList(),
    val problems: Set<HistoryProblem> = emptySet(),
)
internal data class MeasuredNativePressureHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)

private const val MAX_MEASURED_CANDIDATES = 100
