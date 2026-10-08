package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.AmbientStepsEffectiveGapInterval
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MeasuredBackgroundStepsHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow>, val problems: Set<HistoryProblem>,
)

/** Original count windows and retained timed holes; caller owns the Room snapshot. */
internal class MeasuredBackgroundStepsHistoryReader(private val database: AppDatabase) {
    suspend fun candidates(range: HistoryRange, after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        val origins = range.origins.filter { it in setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED) }
        if (HistorySource.STEPS !in range.sources || HistoryPurpose.BACKGROUND !in range.purposes || origins.isEmpty()) return emptyList()
        val arguments = mutableListOf<Any>(range.fromInclusiveMs, range.toExclusiveMs).apply {
            addAll(origins.map { it.name }); add(range.toExclusiveMs); add(range.fromInclusiveMs)
        }
        val seek = if (after == null) "" else """
            AND (startTimeMs < ? OR (startTimeMs = ? AND
                (lower(origin) || ':' || kind > ? OR (lower(origin) || ':' || kind = ? AND identity > ?))))
        """.trimIndent()
        if (after != null) arguments += listOf(after.startTimeMs, after.startTimeMs,
            after.originTag, after.originTag, after.stableId)
        val sql = """
            SELECT * FROM (${nativeFactsSql()} UNION ALL ${nativeGapsSql()}
                UNION ALL ${importedSql(gaps = false)} UNION ALL ${importedSql(gaps = true)})
            WHERE origin IN (${origins.joinToString(",") { "?" }})
                AND startTimeMs < ? AND endTimeMs > ? AND endTimeMs > startTimeMs
            $seek ORDER BY startTimeMs DESC, lower(origin) || ':' || kind ASC, identity ASC LIMIT 101
        """.trimIndent()
        // Global History tags are lowercase; the seek compares exactly the shared position tag.
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery(
            sql, arguments.toTypedArray()))
    }

    suspend fun qualify(keys: List<MeasuredHistoryCandidate>, range: HistoryRange): MeasuredBackgroundStepsHistoryRead {
        require(keys.size <= 100 && keys.map(::measuredHistoryPosition).distinct().size == keys.size)
        require(keys.all { it.kind in setOf("ambient_steps", "ambient_steps_gap", "imported_ambient_steps",
            "imported_ambient_steps_gap") && it.purpose == "BACKGROUND" && it.origin in setOf("LOCAL", "IMPORTED") &&
            (it.origin != "LOCAL" || it.rowId != null) && (it.origin != "IMPORTED" || it.writerId != null) })
        if (HistorySource.STEPS !in range.sources || HistoryPurpose.BACKGROUND !in range.purposes)
            return MeasuredBackgroundStepsHistoryRead(emptyMap(), emptySet())
        val selectedKeys = keys.filter { HistoryOrigin.valueOf(it.origin) in range.origins }
        if (selectedKeys.isEmpty()) return MeasuredBackgroundStepsHistoryRead(emptyMap(), emptySet())
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = linkedSetOf<HistoryProblem>()
        val evidence = database.sourceEvidenceStateDao().get() ?: return MeasuredBackgroundStepsHistoryRead(
            emptyMap(), setOf(problem(HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        // Registration/day parents are independent; an invalid sibling cannot erase a valid count.
        for ((_, selected) in selectedKeys.groupBy { if (it.origin == "LOCAL") "local:${it.rowId}" else "imported:${it.writerId}" }) {
            currentCoroutineContext().ensureActive()
            try {
                val accepted = if (selected.first().origin == "LOCAL") isolatedNative(selected, evidence, range, problems)
                    else imported(selected, evidence, range, problems)
                rows.putAll(accepted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                problems += problem(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            } catch (_: IllegalStateException) {
                problems += problem(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            } catch (_: ArithmeticException) {
                problems += problem(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            } catch (_: Exception) {
                problems += problem(HistoryProblemReason.STORAGE_UNAVAILABLE)
            }
        }
        return MeasuredBackgroundStepsHistoryRead(rows, problems)
    }

    /** At most 199 attempts for 100 original leaves; no result is borrowed from another source. */
    private suspend fun isolatedNative(keys: List<MeasuredHistoryCandidate>, evidence: SourceEvidenceState,
        range: HistoryRange, problems: MutableSet<HistoryProblem>): Map<HistoryPosition, UnifiedHistoryRow> {
        try {
            return native(keys, evidence, range, problems)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (keys.size > 1) {
                val midpoint = (keys.size + 1) / 2
                return isolatedNative(keys.take(midpoint), evidence, range, problems) +
                    isolatedNative(keys.drop(midpoint), evidence, range, problems)
            }
            problems += problem(if (failure is IllegalArgumentException || failure is IllegalStateException ||
                failure is ArithmeticException) HistoryProblemReason.INTEGRITY_UNAVAILABLE else HistoryProblemReason.STORAGE_UNAVAILABLE)
            return emptyMap()
        }
    }

    private suspend fun native(keys: List<MeasuredHistoryCandidate>, evidence: SourceEvidenceState,
        range: HistoryRange, problems: MutableSet<HistoryProblem>): Map<HistoryPosition, UnifiedHistoryRow> {
        val factKeys = keys.filter { it.kind == "ambient_steps" }
        val gapKeys = keys.filter { it.kind == "ambient_steps_gap" }
        val ids = factKeys.map { requireNotNull(it.factId) }
        val facts = if (ids.isEmpty()) emptyList() else database.ambientStepsFactRevisionDao().latestEffectiveForIds(
            AmbientStepsFactRevisionEntity.WRITER_ID, AmbientStepsFactRevisionEntity.WRITER_VERSION, ids, ids.size + 1)
        check(facts.size <= ids.size)
        val gaps = selectedNativeGaps(gapKeys)
        val days = (facts.map { AmbientStepsDayIdentity(requireNotNull(it.structuralEpochDay), requireNotNull(it.storedZoneId),
            requireNotNull(it.structuralDayStartTimeMs), requireNotNull(it.structuralDayEndTimeMs)) } +
            gaps.flatMap { gap -> listOf(dayContaining(gap.gapStartTimeMs, range.viewZoneId),
                dayContaining(gap.gapEndTimeMs - 1L, range.viewZoneId)) }).distinct()
        if (days.isEmpty()) return emptyMap()
        val read = NativeAmbientStepsRangeReader(database).readSelected(AmbientStepsRequestedRange(days), evidence, facts, gaps)
        if (read !is NativeAmbientStepsRangeRead.Ready) {
            if (keys.size > 1) {
                val midpoint = (keys.size + 1) / 2
                return isolatedNative(keys.take(midpoint), evidence, range, problems) +
                    isolatedNative(keys.drop(midpoint), evidence, range, problems)
            }
            problems += problem(if (read is NativeAmbientStepsRangeRead.DependencyOverflow)
                HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            return emptyMap()
        }
        if (read.unverifiableDays.isNotEmpty()) problems += problem(HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (fact in read.factsByDay.values.flatten()) {
            val key = factKeys.singleOrNull { it.factId == fact.logicalFactId && it.factRevision == fact.correctionRevision } ?: continue
            check(key.identity == framed(fact.logicalFactId, fact.correctionRevision.toString()))
            check(key.startTimeMs == fact.startTimeMs && key.endTimeMs == fact.endTimeMs)
            if (overlaps(key, range)) accepted[measuredHistoryPosition(key)] = countRow(key, fact.stepCount)
        }
        for (gap in read.qualifiedMeasuredGaps) {
            val key = gapKeys.singleOrNull { it.factId == gap.gapId && it.startTimeMs == gap.gapStartTimeMs &&
                it.endTimeMs == gap.gapEndTimeMs } ?: continue
            check(key.identity == framed(gap.gapId, gap.gapStartTimeMs.toString(), gap.gapEndTimeMs.toString()))
            if (overlaps(key, range)) accepted[measuredHistoryPosition(key)] = gapRow(key, gap.reason)
        }
        return accepted
    }

    private suspend fun imported(keys: List<MeasuredHistoryCandidate>, evidence: SourceEvidenceState,
        range: HistoryRange, problems: MutableSet<HistoryProblem>): Map<HistoryPosition, UnifiedHistoryRow> {
        val parent = requireNotNull(keys.first().writerId)
        check(keys.all { it.writerId == parent })
        val dao = database.importedAmbientStepsDao()
        val header = dao.latestDaysForIdentities(listOf(parent), 2).singleOrNull() ?: return emptyMap()
        val day = AmbientStepsDayIdentity(header.structuralEpochDay, header.storedZoneId,
            header.structuralDayStartTimeMs, header.structuralDayEndTimeMs)
        val read = ImportedAmbientStepsRangeReader(database, dao).read(AmbientStepsRequestedRange(listOf(day)),
            evidence, setOf(parent), selectedMeasurements = true)
        if (read !is ImportedAmbientStepsRangeRead.Ready) {
            problems += problem(if (read is ImportedAmbientStepsRangeRead.DependencyOverflow)
                HistoryProblemReason.DEPENDENCY_OVERFLOW else HistoryProblemReason.INTEGRITY_UNAVAILABLE)
            return emptyMap()
        }
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (fact in read.factsByDay.values.flatten()) {
            val key = keys.singleOrNull { it.kind == "imported_ambient_steps" && it.factId == fact.logicalFactId &&
                it.factRevision == fact.correctionRevision } ?: continue
            check(key.identity == framed(parent, fact.correctionRevision.toString(), fact.logicalFactId))
            check(key.startTimeMs == fact.startTimeMs && key.endTimeMs == fact.endTimeMs)
            if (evidence.retainedFromMs?.let { fact.startTimeMs < it } != true && overlaps(key, range))
                accepted[measuredHistoryPosition(key)] = countRow(key, fact.stepCount)
        }
        for (gap in read.measuredGapsByDay.values.flatten()) {
            val start = maxOf(gap.intervalStartTimeMs, evidence.retainedFromMs ?: gap.intervalStartTimeMs)
            val key = keys.singleOrNull { it.kind == "imported_ambient_steps_gap" && it.factId == gap.identity.value &&
                it.factRevision == header.importRevision } ?: continue
            check(key.identity == framed(parent, header.importRevision.toString(), gap.identity.value))
            check(key.startTimeMs == start && key.endTimeMs == gap.intervalEndTimeMs)
            if (start < gap.intervalEndTimeMs && overlaps(key, range))
                accepted[measuredHistoryPosition(key)] = gapRow(key, gap.reason.name)
        }
        return accepted
    }

    private suspend fun selectedNativeGaps(keys: List<MeasuredHistoryCandidate>): List<AmbientStepsEffectiveGapInterval> {
        if (keys.isEmpty()) return emptyList()
        val ids = keys.map { requireNotNull(it.factId) }.distinct()
        val arguments = mutableListOf<Any>().apply { addAll(ids) }
        val exact = keys.joinToString(" OR ") { key ->
            arguments += listOf(requireNotNull(key.factId), key.startTimeMs, requireNotNull(key.endTimeMs))
            "(gap_id = ? AND gap_start_time_ms = ? AND gap_end_time_ms = ?)"
        }
        return database.ambientStepsImportStateDao().selectedEffectiveGapIntervals(SimpleSQLiteQuery("""
            SELECT * FROM (${remainingGapSql("WHERE gap_id IN (${ids.joinToString(",") { "?" }})")})
            WHERE $exact ORDER BY gap_start_time_ms DESC, gap_id ASC LIMIT 101
        """.trimIndent(), arguments.toTypedArray())).also { check(it.size <= keys.size) }
    }

    private fun countRow(key: MeasuredHistoryCandidate, count: Long) = row(key, HistoryMeasurement.Steps(StepsHistory(
        count, if (key.origin == "IMPORTED") HistoryAvailability.RETAINED_IMPORTED else HistoryAvailability.AVAILABLE,
        if (count == 0L) HistoryEvidence.ACTIVE else HistoryEvidence.RECORDED,
        HistoryProductState.READY, StepsHistoryCoverage.COMPLETE)))

    private fun gapRow(key: MeasuredHistoryCandidate, reason: String) = row(key, HistoryMeasurement.Gap(HistorySource.STEPS, reason))
    private fun row(key: MeasuredHistoryCandidate, measurement: HistoryMeasurement) = UnifiedHistoryRow(
        measuredHistoryPosition(key), key.endTimeMs, setOf(HistoryOrigin.valueOf(key.origin)), HistoryPurpose.BACKGROUND,
        sections = emptyList(), measurement = measurement)
    private fun overlaps(key: MeasuredHistoryCandidate, range: HistoryRange) =
        key.startTimeMs < range.toExclusiveMs && requireNotNull(key.endTimeMs) > range.fromInclusiveMs
    private fun problem(reason: HistoryProblemReason) = HistoryProblem(HistorySource.STEPS, reason)
    private fun framed(vararg values: String) = values.joinToString("") { "${it.length}:$it" }
    private fun sqlFrame(vararg values: String) = values.joinToString(" || ") { "length(CAST($it AS TEXT)) || ':' || CAST($it AS TEXT)" }
    private fun dayContaining(timeMs: Long, zoneId: String): AmbientStepsDayIdentity {
        val zone = ZoneId.of(zoneId)
        val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        return AmbientStepsDayIdentity(date.toEpochDay(), zoneId, date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    private fun nativeFactsSql() = """
        SELECT 'ambient_steps' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
            ${sqlFrame("fact.logical_fact_id", "fact.semantic_revision")} AS identity,
            fact.window_start_time_ms AS startTimeMs, fact.window_end_time_ms AS endTimeMs,
            fact.registration_generation AS rowId, fact.writer_id AS writerId, fact.writer_version AS writerVersion,
            fact.logical_fact_id AS factId, fact.semantic_revision AS factRevision, NULL AS itemIndex,
            NULL AS physicalEventId, NULL AS physicalIdentity
        FROM ambient_steps_fact_revision fact WHERE fact.operation = 'UPSERT'
            AND fact.writer_id = '${AmbientStepsFactRevisionEntity.WRITER_ID}'
            AND fact.writer_version = ${AmbientStepsFactRevisionEntity.WRITER_VERSION}
            AND fact.semantic_revision = (SELECT MAX(latest.semantic_revision) FROM ambient_steps_fact_revision latest
                WHERE latest.writer_id = fact.writer_id AND latest.writer_version = fact.writer_version
                    AND latest.logical_fact_id = fact.logical_fact_id)
    """.trimIndent()

    private fun nativeGapsSql() = """
        SELECT 'ambient_steps_gap' AS kind, 'LOCAL' AS origin, 'BACKGROUND' AS purpose,
            ${sqlFrame("gap_id", "gap_start_time_ms", "gap_end_time_ms")} AS identity,
            gap_start_time_ms AS startTimeMs, gap_end_time_ms AS endTimeMs,
            registration_generation AS rowId, gap_id AS writerId, NULL AS writerVersion,
            gap_id AS factId, NULL AS factRevision, NULL AS itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity
        FROM (${remainingGapSql("WHERE gap_end_time_ms > ? AND gap_start_time_ms < ?")})
    """.trimIndent()

    private fun importedSql(gaps: Boolean): String {
        val table = if (gaps) "imported_ambient_steps_gap" else "imported_ambient_steps_fact"
        val id = if (gaps) "gap_identity" else "fact_identity"
        val kind = if (gaps) "imported_ambient_steps_gap" else "imported_ambient_steps"
        val start = if (gaps) "MAX(item.interval_start_time_ms, COALESCE((SELECT retained_from_ms FROM source_evidence_state WHERE id = 1), item.interval_start_time_ms))"
            else "item.interval_start_time_ms"
        return """
            SELECT '$kind' AS kind, 'IMPORTED' AS origin, 'BACKGROUND' AS purpose,
                ${sqlFrame("header.day_identity", "header.import_revision", "item.$id")} AS identity,
                $start AS startTimeMs, item.interval_end_time_ms AS endTimeMs,
                NULL AS rowId, header.day_identity AS writerId, NULL AS writerVersion,
                item.$id AS factId, header.import_revision AS factRevision, NULL AS itemIndex,
                NULL AS physicalEventId, NULL AS physicalIdentity
            FROM $table item JOIN imported_ambient_steps_day_revision header ON header.day_identity = item.day_identity
                AND header.import_revision = item.day_import_revision
            WHERE header.import_revision = (SELECT MAX(latest.import_revision) FROM imported_ambient_steps_day_revision latest
                WHERE latest.day_identity = header.day_identity)
        """.trimIndent()
    }

    /** Same source subtraction as the day reader, without selected-date clipping. */
    private fun remainingGapSql(scope: String) = """
            WITH effective_fact AS (SELECT fact.* FROM ambient_steps_fact_revision AS fact WHERE fact.operation = 'UPSERT' AND
            fact.semantic_revision = (SELECT MAX(state.semantic_revision) FROM ambient_steps_fact_revision AS state WHERE
            state.writer_id = fact.writer_id AND state.writer_version = fact.writer_version AND state.logical_fact_id =
            fact.logical_fact_id)), scoped_gap AS (SELECT * FROM ambient_steps_import_gap $scope), boundary AS (SELECT
            gap.gap_id, gap.gap_start_time_ms AS boundary_time_ms FROM scoped_gap AS gap UNION SELECT gap.gap_id,
            MIN(fact.window_end_time_ms, gap.gap_end_time_ms) FROM scoped_gap AS gap JOIN effective_fact AS fact ON
            fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id AND fact.registration_generation
            = gap.registration_generation AND fact.collected_data_epoch = gap.collected_data_epoch AND fact.window_end_time_ms
            > gap.gap_start_time_ms AND fact.window_start_time_ms < gap.gap_end_time_ms) SELECT gap.gap_id AS gap_id,
            gap.registration_generation AS registration_generation, gap.gap_sequence AS gap_sequence, gap.provider AS provider,
            gap.source_instance_id AS source_instance_id, gap.reason AS reason, gap.gap_start_time_ms AS
            declared_gap_start_time_ms, gap.gap_end_time_ms AS declared_gap_end_time_ms, MAX(boundary.boundary_time_ms,
            COALESCE((SELECT retained_from_ms FROM source_evidence_state WHERE id = 1), boundary.boundary_time_ms)) AS
            gap_start_time_ms, MIN(gap.gap_end_time_ms, COALESCE((SELECT MIN(fact.window_start_time_ms) FROM effective_fact AS
            fact WHERE fact.window_start_time_ms > boundary.boundary_time_ms AND fact.provider = gap.provider AND
            fact.source_instance_id = gap.source_instance_id AND fact.registration_generation = gap.registration_generation AND
            fact.collected_data_epoch = gap.collected_data_epoch AND fact.window_start_time_ms < gap.gap_end_time_ms AND
            fact.window_end_time_ms > gap.gap_start_time_ms), gap.gap_end_time_ms)) AS gap_end_time_ms,
            gap.predecessor_registration_generation AS predecessor_registration_generation, gap.predecessor_provider AS
            predecessor_provider, gap.previous_clock_domain_id AS previous_clock_domain_id, gap.next_clock_domain_id AS
            next_clock_domain_id, gap.previous_zone_id AS previous_zone_id, gap.next_zone_id AS next_zone_id,
            gap.collected_data_epoch AS collected_data_epoch, gap.recorded_at_ms AS recorded_at_ms FROM boundary JOIN
            scoped_gap AS gap ON gap.gap_id = boundary.gap_id WHERE boundary.boundary_time_ms < gap.gap_end_time_ms AND NOT
            EXISTS (SELECT 1 FROM effective_fact AS fact WHERE fact.window_start_time_ms <= boundary.boundary_time_ms AND
            fact.provider = gap.provider AND fact.source_instance_id = gap.source_instance_id AND fact.registration_generation
            = gap.registration_generation AND fact.collected_data_epoch = gap.collected_data_epoch AND fact.window_end_time_ms
            > boundary.boundary_time_ms AND fact.window_end_time_ms > gap.gap_start_time_ms AND fact.window_start_time_ms <
            gap.gap_end_time_ms)
    """.trimIndent()
}
