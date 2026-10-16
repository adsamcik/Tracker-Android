package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
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

/** Exact native Activity bands; source qualification precedes date selection and presentation. */
internal class MeasuredNativeActivityHistoryReader(
    private val database: AppDatabase,
    private val activity: DefaultActivityHistoryRepository,
) {
    suspend fun candidates(range: HistoryRange, after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        if (HistorySource.ACTIVITY !in range.sources || HistoryOrigin.LOCAL !in range.origins ||
            HistoryPurpose.CAPTURE !in range.purposes) {
            return emptyList()
        }
        val seek = if (after == null) {
            ""
        } else {
            """AND (startTimeMs < ? OR (startTimeMs = ? AND
            ('local:activity' > ? OR ('local:activity' = ? AND identity > ?))))"""
        }
        val args = mutableListOf<Any?>(range.toExclusiveMs, range.fromInclusiveMs, range.fromInclusiveMs)
        if (after != null) {
            args.addAll(listOf(after.startTimeMs, after.startTimeMs, after.originTag, after.originTag, after.stableId))
        }
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery("""
            SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId,
                writerId, writerVersion, factId, factRevision, itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity FROM (
            SELECT 'activity' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
                'activity:' || length(f.writer_projection_id) || ':' || f.writer_projection_id || ':' ||
                f.writer_projection_version || ':' || length(f.logical_window_id) || ':' || f.logical_window_id || ':' ||
                f.semantic_revision || ':' || printf('%020d', f.fragment_ordinal) AS identity,
                f.start_wall_time_ms AS startTimeMs, f.end_wall_time_ms AS endTimeMs,
                v.session_segment_id AS rowId, f.writer_projection_id AS writerId,
                f.writer_projection_version AS writerVersion, f.logical_window_id AS factId,
                f.semantic_revision AS factRevision, f.fragment_ordinal AS itemIndex
            FROM activity_captured_fragment f LEFT JOIN activity_captured_window_revision v
                ON v.writer_projection_id = f.writer_projection_id AND v.writer_projection_version = f.writer_projection_version
                AND v.logical_window_id = f.logical_window_id AND v.semantic_revision = f.semantic_revision
            WHERE f.fragment_kind = 'BAND' AND f.start_wall_time_ms >= 0 AND f.end_wall_time_ms >= 0
                AND f.start_wall_time_ms < ? AND (f.end_wall_time_ms >= ? OR
                    f.end_wall_time_ms < f.start_wall_time_ms AND f.start_wall_time_ms >= ?)
                AND NOT EXISTS (SELECT 1 FROM activity_captured_window_revision newer
                    WHERE newer.writer_projection_id = f.writer_projection_id AND
                        newer.writer_projection_version = f.writer_projection_version AND
                        newer.logical_window_id = f.logical_window_id AND newer.semantic_revision > f.semantic_revision))
            WHERE 1 $seek ORDER BY startTimeMs DESC, identity LIMIT 101
            """, args.toTypedArray()))
    }

    suspend fun qualify(keys: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredNativeActivityHistoryRead {
        requireNativeCapturedCandidates(keys, "activity", MAX_MEASURED_CANDIDATES)
        if (keys.isEmpty() || HistorySource.ACTIVITY !in range.sources || HistoryOrigin.LOCAL !in range.origins ||
            HistoryPurpose.CAPTURE !in range.purposes) {
            return MeasuredNativeActivityHistoryRead()
        }
        val groups = keys.groupBy { ActivityRevisionKey(requireNotNull(it.writerId), requireNotNull(it.writerVersion),
            requireNotNull(it.factId), requireNotNull(it.factRevision)) }
        val read = readIndependent(groups.keys.toList())
        val names = MeasuredNativeSessionNames.load(database,
            read.windows.values.map { it.revision.logicalTrackingId })
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = historyProblems(read)
        for ((identity, candidates) in groups) {
            val window = read.windows[identity] ?: continue
            appendMeasuredBands(window, candidates, range, names, rows, problems)
        }
        return MeasuredNativeActivityHistoryRead(rows, problems)
    }

    private fun historyProblems(read: ActivityMeasuredWindowsRead): MutableSet<HistoryProblem> {
        val problems = read.causes.filter { it !in setOf(ActivityHistoryCause.RETENTION_LIMIT,
            ActivityHistoryCause.DELETED,
            ActivityHistoryCause.PRIVACY_EPOCH_MISMATCH) }.mapTo(linkedSetOf()) { HistoryProblem(HistorySource.ACTIVITY,
                if (it == ActivityHistoryCause.READ_BUDGET_EXCEEDED) {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                }
                else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                }) }
        if (read.storageFailed) {
            problems += HistoryProblem(HistorySource.ACTIVITY, HistoryProblemReason.STORAGE_UNAVAILABLE)
        }
        return problems
    }

    private fun appendMeasuredBands(
        window: ActivityMeasuredWindow,
        candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        names: MeasuredNativeSessionNames,
        rows: MutableMap<HistoryPosition, UnifiedHistoryRow>,
        problems: MutableSet<HistoryProblem>,
    ) {
        if (window.fragments.values.any { it is ActivityHistoryFragment.Gap })
            {
            problems += HistoryProblem(HistorySource.ACTIVITY, HistoryProblemReason.TIME_UNCERTAIN)
        }
        for (key in candidates) {
            val band = window.fragments[key.itemIndex] as? ActivityHistoryFragment.Band
            if (band == null) {
                problems += HistoryProblem(HistorySource.ACTIVITY, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                continue
            }
            val start = band.startTime.raw
            val end = band.endTime.raw
            if (hasInvalidBandPosition(key, window, start, end, range)) {
                problems += HistoryProblem(HistorySource.ACTIVITY, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                continue
            }
            val position = measuredHistoryPosition(key)
            rows[position] = UnifiedHistoryRow(position, end, setOf(HistoryOrigin.LOCAL), HistoryPurpose.CAPTURE,
                name = names.window(window.revision.logicalTrackingId, start, end), sections = emptyList(),
                measurement = HistoryMeasurement.Activity(band))
        }
    }

    private fun hasInvalidBandPosition(
        key: MeasuredHistoryCandidate,
        window: ActivityMeasuredWindow,
        start: Long,
        end: Long,
        range: HistoryRange,
    ): Boolean = key.rowId != window.revision.sessionSegmentId || start != key.startTimeMs || end != key.endTimeMs ||
        start >= range.toExclusiveMs || end >= start && end < range.fromInclusiveMs ||
        end < start && start < range.fromInclusiveMs

    /** Split a failing selected batch only; malformed windows cannot hide independently valid siblings. */
    private suspend fun readIndependent(keys: List<ActivityRevisionKey>): ActivityMeasuredWindowsRead {
        if (keys.isEmpty()) {
            return ActivityMeasuredWindowsRead()
        }
        val result = try { activity.selectMeasuredWindowsInTransaction(keys) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: android.database.sqlite.SQLiteException) { ActivityMeasuredWindowsRead(storageFailed = true) }
        catch (_: Exception) { ActivityMeasuredWindowsRead(causes = setOf(ActivityHistoryCause.FACT_INTEGRITY_FAILED)) }
        if (keys.size == 1 || result.windows.keys.containsAll(keys)) {
            return result
        }
        val middle = keys.size / 2
        val left = readIndependent(keys.take(middle))
        val right = readIndependent(keys.drop(middle))
        return ActivityMeasuredWindowsRead(left.windows + right.windows, left.causes + right.causes,
            left.storageFailed || right.storageFailed)
    }
}

internal data class MeasuredNativeActivityHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)

private const val MAX_MEASURED_CANDIDATES = 100
