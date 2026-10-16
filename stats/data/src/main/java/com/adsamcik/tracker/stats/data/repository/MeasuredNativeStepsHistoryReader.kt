package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateIdentity
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

/** Original count windows; neither day browsing nor paging invents a share of an interval. */
internal class MeasuredNativeStepsHistoryReader(
    private val database: AppDatabase,
    private val steps: StepsSegmentHistorySelector,
) {
    suspend fun candidates(range: HistoryRange, after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        if (!includesNativeSteps(range)) {
            return emptyList()
        }
        val seek = if (after == null) {
            ""
        } else {
            """AND (startTimeMs < ? OR (startTimeMs = ? AND
            ('local:steps' > ? OR ('local:steps' = ? AND identity > ?))))"""
        }
        val args = mutableListOf<Any?>(range.toExclusiveMs, range.fromInclusiveMs)
        if (after != null) {
            args.addAll(listOf(after.startTimeMs, after.startTimeMs, after.originTag, after.originTag, after.stableId))
        }
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery("""
            SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId,
                writerId, writerVersion, factId, factRevision, itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity FROM (
            SELECT 'steps' AS kind, 'LOCAL' AS origin, 'CAPTURE' AS purpose,
                'steps:' || length(f.writer_projection_id) || ':' || f.writer_projection_id || ':' ||
                f.writer_projection_version || ':' || length(f.logical_fact_id) || ':' || f.logical_fact_id || ':' ||
                f.semantic_revision AS identity,
                f.interval_start_time_ms AS startTimeMs, f.interval_end_time_ms AS endTimeMs,
                r.session_segment_id AS rowId, f.writer_projection_id AS writerId,
                f.writer_projection_version AS writerVersion, f.logical_fact_id AS factId,
                f.semantic_revision AS factRevision, NULL AS itemIndex
            FROM step_fact_revision f LEFT JOIN source_service_run r ON r.service_run_id = f.service_run_id
            WHERE f.origin_kind = 'LIVE_WAL' AND f.operation = 'UPSERT'
                AND f.interval_start_time_ms >= 0 AND f.interval_start_time_ms < ? AND f.interval_end_time_ms >= ?
                AND NOT EXISTS (SELECT 1 FROM step_fact_revision newer
                    WHERE newer.writer_projection_id = f.writer_projection_id AND
                        newer.writer_projection_version = f.writer_projection_version AND
                        newer.logical_fact_id = f.logical_fact_id AND newer.operation = 'UPSERT' AND
                        newer.semantic_revision > f.semantic_revision))
            WHERE 1 $seek ORDER BY startTimeMs DESC, identity LIMIT 101
            """, args.toTypedArray()))
    }

    suspend fun qualify(keys: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredNativeStepsHistoryRead {
        requireNativeCapturedCandidates(keys, "steps", MAX_MEASURED_CANDIDATES)
        if (keys.isEmpty() || !includesNativeSteps(range)) {
            return MeasuredNativeStepsHistoryRead()
        }
        val selected = keys.groupBy { StepsFactCandidateIdentity(requireNotNull(it.writerId),
            requireNotNull(it.writerVersion).toLong(), requireNotNull(it.factId)) }
        val read = readIndependent(selected)
        val names = MeasuredNativeSessionNames.load(database,
            read.facts.values.mapNotNull { it.segment.logicalTrackingId })
        val floor = database.sourceEvidenceStateDao().get()?.retainedFromMs
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = read.problems.toMutableSet()
        for ((identity, candidates) in selected) {
            val owned = read.facts[identity] ?: continue
            rows.putAll(measuredFactRows(owned, candidates, range, names, floor, problems))
        }
        return MeasuredNativeStepsHistoryRead(rows, problems)
    }

    private fun measuredFactRows(
        owned: StepsMeasuredFactRead,
        candidates: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        names: MeasuredNativeSessionNames,
        floor: Long?,
        problems: MutableSet<HistoryProblem>,
    ): Map<HistoryPosition, UnifiedHistoryRow> {
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val fact = owned.fact ?: return emptyMap()
        if (owned.steps.availability != StepsHistoryAvailability.AVAILABLE ||
            owned.steps.materialization == StepsHistoryMaterialization.FAILED) {
            return emptyMap()
        }
        val start = requireNotNull(fact.intervalStartTimeMs)
        val end = requireNotNull(fact.intervalEndTimeMs)
        if (floor != null && end < floor) {
            return emptyMap()
        }
        for (key in candidates) {
            if (hasInvalidWindowPosition(key, owned.segment.id, fact.semanticRevision, start, end, range)) {
                problems += HistoryProblem(HistorySource.STEPS, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                continue
            }
            val position = measuredHistoryPosition(key)
            rows[position] = UnifiedHistoryRow(position, end, setOf(HistoryOrigin.LOCAL), HistoryPurpose.CAPTURE,
                name = fact.logicalTrackingId?.let { names.window(it, start, end) }, sections = emptyList(),
                measurement = HistoryMeasurement.Steps(owned.steps.toPublicHistory()))
        }
        return rows
    }

    private fun hasInvalidWindowPosition(
        key: MeasuredHistoryCandidate,
        segmentId: Long,
        revision: Long,
        start: Long,
        end: Long,
        range: HistoryRange,
    ): Boolean = key.rowId != segmentId || key.factRevision != revision ||
        key.startTimeMs != start || key.endTimeMs != end || start >= range.toExclusiveMs ||
        end < range.fromInclusiveMs

    private suspend fun readIndependent(
        selected: Map<StepsFactCandidateIdentity, List<MeasuredHistoryCandidate>>,
    ): StepsMeasuredRead {
        if (selected.isEmpty()) {
            return StepsMeasuredRead()
        }
        val result = readSelected(selected)
        if (selected.size == 1 || selected.keys.all { key -> result.facts[key]?.let {
                it.fact != null && it.steps.availability == StepsHistoryAvailability.AVAILABLE &&
                    it.steps.materialization != StepsHistoryMaterialization.FAILED } == true }) {
            return result
        }
        val keys = selected.keys.toList()
        val middle = keys.size / 2
        val left = readIndependent(keys.take(middle).associateWith(selected::getValue))
        val right = readIndependent(keys.drop(middle).associateWith(selected::getValue))
        return StepsMeasuredRead(left.facts + right.facts, left.problems + right.problems)
    }

    private suspend fun readSelected(
        selected: Map<StepsFactCandidateIdentity, List<MeasuredHistoryCandidate>>,
    ): StepsMeasuredRead =
        try {
            val segmentIds = selected.values.flatten().mapNotNull { it.rowId?.takeIf { id -> id > 0L } }.distinct()
            val facts = steps.selectMeasuredFactsInTransaction(segmentIds, selected.keys.toList())
            val problems = selectedFactProblems(selected.keys, facts)
            StepsMeasuredRead(facts, problems)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: android.database.sqlite.SQLiteException) { StepsMeasuredRead(problems = setOf(
            HistoryProblem(HistorySource.STEPS, HistoryProblemReason.STORAGE_UNAVAILABLE))) }
        catch (_: Exception) { StepsMeasuredRead(problems = setOf(
            HistoryProblem(HistorySource.STEPS, HistoryProblemReason.INTEGRITY_UNAVAILABLE))) }

    /** Project qualified saved-fact outcomes after the source reader completes. */
    private fun selectedFactProblems(
        expected: Set<StepsFactCandidateIdentity>,
        facts: Map<StepsFactCandidateIdentity, StepsMeasuredFactRead>,
    ): Set<HistoryProblem> {
        val problems = linkedSetOf<HistoryProblem>()
        if (expected.any { it !in facts }) {
            problems += HistoryProblem(HistorySource.STEPS,
            HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        }
        for (fact in facts.values) {
            if (fact.steps.availability in setOf(StepsHistoryAvailability.AVAILABLE,
                StepsHistoryAvailability.DELETED) &&
                fact.steps.materialization != StepsHistoryMaterialization.FAILED) {
                continue
            }
            if (fact.steps.reasons.all { it in QUIET_ABSENCE_REASONS }) {
                continue
            }
            problems += HistoryProblem(HistorySource.STEPS,
                if (StepsHistoryReason.BATCH_DEPENDENCY_OVERFLOW in fact.steps.reasons)
                    {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                } else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                })
        }
        return problems
    }

    private fun includesNativeSteps(range: HistoryRange): Boolean = HistorySource.STEPS in range.sources &&
        HistoryOrigin.LOCAL in range.origins && HistoryPurpose.CAPTURE in range.purposes

    private companion object {
        val QUIET_ABSENCE_REASONS = setOf(StepsHistoryReason.OUTSIDE_RETAINED_FLOOR,
            StepsHistoryReason.DELETED_FACTS, StepsHistoryReason.STALE_COLLECTED_DATA_EPOCH)
    }
}

private data class StepsMeasuredRead(
    val facts: Map<StepsFactCandidateIdentity, StepsMeasuredFactRead> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)
internal data class MeasuredNativeStepsHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)

private const val MAX_MEASURED_CANDIDATES = 100
