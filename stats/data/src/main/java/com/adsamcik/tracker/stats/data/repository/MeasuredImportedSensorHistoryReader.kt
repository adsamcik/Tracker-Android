package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductReader
import com.adsamcik.tracker.shared.base.database.PortableActivityCaptureCoverage
import com.adsamcik.tracker.shared.base.database.PortableActivityFragmentV1
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityWindowV1
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
import com.adsamcik.tracker.shared.base.database.data.NativePortableOwnership
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsReadFailure
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedRead
import com.adsamcik.tracker.shared.base.database.steps.imported.ImportedStepsRetainedReader
import com.adsamcik.tracker.shared.base.database.steps.imported.RetainedImportedStepsEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
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
import com.adsamcik.tracker.stats.api.repository.PortablePressureAvailability
import com.adsamcik.tracker.stats.api.repository.PortablePressureRunV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortablePressureWindowV1
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class MeasuredImportedSensorHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow>,
    val problems: Set<HistoryProblem>,
)

/** Caller owns the Room snapshot. Raw keys never authorize a measurement. */
// One caller-owned Room snapshot qualifies the three imported sources and their isolated parent failures.
@Suppress("TooManyFunctions")
internal class MeasuredImportedSensorHistoryReader(private val database: AppDatabase) {
    suspend fun candidates(source: HistorySource, range: HistoryRange, after: HistoryPosition?):
        List<MeasuredHistoryCandidate> {
        if (source !in range.sources || HistoryOrigin.IMPORTED !in range.origins ||
            HistoryPurpose.CAPTURE !in range.purposes) {
            return emptyList()
        }
        val leaf = leafSql(source)
        val tag = "imported:${source.name.lowercase().let { "imported_$it" }}"
        // Activity endpoints are observations; a discontinuity may move the original end backwards.
        val overlap = if (source == HistorySource.ACTIVITY) {
            "(endTimeMs >= ? OR startTimeMs >= ?)"
        }
            else {
            "(endTimeMs > ? OR (endTimeMs = startTimeMs AND startTimeMs >= ?))"
        }
        val seek = if (after == null) {
            ""
        } else {
            """
            AND (startTimeMs < ? OR (startTimeMs = ? AND
                (? > ? OR (? = ? AND identity > ?))))
        """.trimIndent()
        }
        val arguments = mutableListOf<Any>(range.toExclusiveMs, range.fromInclusiveMs, range.fromInclusiveMs)
        if (after != null) {
            arguments.addAll(listOf(after.startTimeMs, after.startTimeMs,
            tag, after.originTag, tag, after.originTag, after.stableId))
        }
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery("""
            SELECT * FROM ($leaf)
            WHERE startTimeMs < ? AND $overlap
            $seek ORDER BY startTimeMs DESC, identity ASC LIMIT 101
        """.trimIndent(), arguments.toTypedArray()))
    }

    suspend fun qualify(source: HistorySource, keys: List<MeasuredHistoryCandidate>, range: HistoryRange):
        MeasuredImportedSensorHistoryRead {
        requireImportedCandidates(source, keys)
        if (source !in range.sources || HistoryOrigin.IMPORTED !in range.origins ||
            HistoryPurpose.CAPTURE !in range.purposes)
            {
            return MeasuredImportedSensorHistoryRead(emptyMap(), emptySet())
        }
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = linkedSetOf<HistoryProblem>()
        // One original parent is loaded at a time, then discarded. Sibling failures are isolated.
        for ((parent, selected) in keys.groupBy { requireNotNull(it.writerId) }) {
            currentCoroutineContext().ensureActive()
            appendParent(source, parent, selected, range, rows, problems)
        }
        return MeasuredImportedSensorHistoryRead(rows, problems)
    }

    private fun requireImportedCandidates(source: HistorySource, keys: List<MeasuredHistoryCandidate>) {
        require(keys.size <= MAX_MEASURED_CANDIDATES &&
            keys.map(::measuredHistoryPosition).distinct().size == keys.size)
        require(source in setOf(HistorySource.ACTIVITY, HistorySource.PRESSURE, HistorySource.STEPS))
        require(keys.all { it.kind == "imported_${source.name.lowercase()}" && it.origin == "IMPORTED" &&
            it.purpose == "CAPTURE" && it.writerId != null })
    }

    private suspend fun appendParent(
        source: HistorySource,
        parent: String,
        keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        rows: MutableMap<HistoryPosition, UnifiedHistoryRow>,
        problems: MutableSet<HistoryProblem>,
    ) {
        try {
            val qualified = when (source) {
                HistorySource.ACTIVITY -> activity(parent, keys, range, problems)
                HistorySource.PRESSURE -> pressure(parent, keys, range, problems)
                HistorySource.STEPS -> steps(parent, keys, range, problems)
                else -> error("Unsupported imported measured source")
            }
            rows.putAll(qualified)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        } catch (_: IllegalStateException) {
            problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        } catch (_: ArithmeticException) {
            problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
        } catch (_: Exception) {
            problems += HistoryProblem(source, HistoryProblemReason.STORAGE_UNAVAILABLE)
        }
    }

    private suspend fun activity(parent: String, keys: List<MeasuredHistoryCandidate>, range: HistoryRange,
        problems: MutableSet<HistoryProblem>): Map<HistoryPosition, UnifiedHistoryRow> {
        val read = ImportedActivityProductReader(database)
            .selectIdentityInTransaction(PortableActivityOpaqueIdentity(parent))
        if (read !is ImportedActivityProductEvaluation.Readable) {
            if (read is ImportedActivityProductEvaluation.Unverifiable) {
                problems += HistoryProblem(
                HistorySource.ACTIVITY, if (read.reason == ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
                    {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                } else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                })
            }
            return emptyMap()
        }
        if (read.entryDeleted) {
            return emptyMap()
        }
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (run in read.entry.runs) {
            if (run.identity.value in read.deletedRunIdentities ||
                run.captureCoverage == PortableActivityCaptureCoverage.NOT_CAPTURED) {
                continue
            }
            for (window in run.windows) {
                accepted.putAll(read.activityWindow(parent, run.identity.value, window, keys, range, problems))
            }
        }
        return accepted
    }

    private fun ImportedActivityProductEvaluation.Readable.activityWindow(
        parent: String,
        runIdentity: String,
        window: PortableActivityWindowV1,
        keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
        problems: MutableSet<HistoryProblem>,
    ): Map<HistoryPosition, UnifiedHistoryRow> {
        val revision = candidate.importRevision
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for ((index, fragment) in window.fragments.withIndex()) {
            if (fragment !is PortableActivityFragmentV1.Band) {
                continue
            }
            val key = keys.singleOrNull { it.identity == framed(parent, revision.toString(),
                runIdentity, window.identity.value, index.toString()) } ?: continue
            requireActivityFragmentPosition(key, revision, window, index, fragment)
            if (!overlaps(key, range) || fragment.precedesRetainedFloor(retainedFromMs)) {
                continue
            }
            if (window.fragments.any { it is PortableActivityFragmentV1.Gap }) {
                // Elapsed-only gaps have no trustworthy UTC location in a calendar.
                problems += HistoryProblem(HistorySource.ACTIVITY, HistoryProblemReason.TIME_UNCERTAIN)
            }
            val band = fragment.toMeasuredPublicFragment(window.storedZoneId) as ActivityHistoryFragment.Band
            val position = measuredHistoryPosition(key)
            accepted[position] = row(key, HistoryMeasurement.Activity(band))
        }
        return accepted
    }

    private fun PortableActivityFragmentV1.Band.precedesRetainedFloor(floor: Long?): Boolean =
        floor?.let {
            earliest(startWallTimeMs, startWallTimeUncertaintyMs) < it ||
                earliest(endWallTimeMs, endWallTimeUncertaintyMs) < it
        } == true

    private fun requireActivityFragmentPosition(
        key: MeasuredHistoryCandidate,
        revision: Long,
        window: PortableActivityWindowV1,
        index: Int,
        fragment: PortableActivityFragmentV1.Band,
    ) {
        check(key.factRevision == revision && key.factId == window.identity.value && key.itemIndex == index)
        check(key.startTimeMs == fragment.startWallTimeMs && key.endTimeMs == fragment.endWallTimeMs)
    }

    private suspend fun pressure(parent: String, keys: List<MeasuredHistoryCandidate>, range: HistoryRange,
        problems: MutableSet<HistoryProblem>): Map<HistoryPosition, UnifiedHistoryRow> {
        val read = ImportedPressureHistoryEvaluator(database).selectedInTransaction(parent)
        if (read !is ImportedPressureHistoryEvaluation.Readable) {
            if (read is ImportedPressureHistoryEvaluation.Unverifiable) {
                problems += HistoryProblem(
                HistorySource.PRESSURE, if (read.reason == ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW)
                    {
                    HistoryProblemReason.DEPENDENCY_OVERFLOW
                } else {
                    HistoryProblemReason.INTEGRITY_UNAVAILABLE
                })
            }
            return emptyMap()
        }
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (run in read.latest.entry.runs) {
            if (run.identity.value in read.deletedRunIdentities ||
                run.availability == PortablePressureAvailability.DELETED) {
                continue
            }
            accepted.putAll(read.pressureRun(parent, run, keys, range))
        }
        return accepted
    }

    private suspend fun ImportedPressureHistoryEvaluation.Readable.pressureRun(
        parent: String,
        run: PortablePressureRunV1,
        keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange,
    ): Map<HistoryPosition, UnifiedHistoryRow> {
        val revision = latest.header.importRevision
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for (window in run.windows) {
            val key = keys.singleOrNull { it.identity == framed(parent, revision.toString(),
                run.identity.value, window.identity.value) } ?: continue
            check(key.factRevision == revision && key.factId == window.identity.value)
            check(key.startTimeMs == window.intervalStartTimeMs && key.endTimeMs == window.intervalEndTimeMs)
            if (!retainedPressureOverlaps(key, window, range, retainedFromMs)) {
                continue
            }
            if (hasVisibleNativePressure(window, range)) {
                continue
            }
            val position = measuredHistoryPosition(key)
            accepted[position] = row(key, HistoryMeasurement.Pressure(window.toPublicWindow()))
        }
        return accepted
    }

    private fun retainedPressureOverlaps(key: MeasuredHistoryCandidate, window: PortablePressureWindowV1,
        range: HistoryRange, floor: Long?): Boolean = overlaps(key, range) && floor?.let {
            earliest(window.intervalStartTimeMs, window.wallTimeUncertaintyMs) < it
        } != true

    private suspend fun hasVisibleNativePressure(window: PortablePressureWindowV1, range: HistoryRange): Boolean {
        if (range.organizationSessionId != null) {
            return false
        }
        val end = window.intervalEndTimeMs
        if (HistoryPurpose.BACKGROUND !in range.purposes ||
            end !in range.fromInclusiveMs until range.toExclusiveMs) {
            return false
        }
        val writer = SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID
        return database.pressureSampleDao().allDayBetween(end, end + 1L, MAX_MEASURED_CANDIDATES + 1).any { native ->
            val event = AllDaySensorObservationIdentity.pressureEventId(native.sourceSignalId) ?: return@any false
            val factId = "$writer:$event"
            if (factId.length > PressurePortableFormatV1.MAX_LOCAL_IDENTITY_LENGTH) {
                return@any false
            }
            val imported = AllDaySensorObservationIdentity.IMPORTED_CAPABILITY in
                native.observationStamp.capabilityFlags.orEmpty().split(',')
            val origin = if (imported) { HistoryOrigin.IMPORTED } else { HistoryOrigin.LOCAL }
            origin in range.origins && native.matches(window) && PortablePressureOpaqueIdentity.derive(
                PortablePressureIdentityKind.WINDOW, NativePortableOwnership.pressureWindowIdentity(writer,
                    SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION, factId)) == window.identity
        }
    }

    private fun PressureSample.matches(window: PortablePressureWindowV1): Boolean = listOf(
        sampleCount == window.sampleCount, pressureHpa == window.meanHectopascals.toFloat(),
        minPressureHpa == window.minimumHectopascals, maxPressureHpa == window.maximumHectopascals,
        standardDeviationHpa == kotlin.math.sqrt(window.sumSquaredDeviations / window.sampleCount).toFloat(),
        windowStartElapsedRealtimeNanos != null, windowEndElapsedRealtimeNanos == elapsedRealtimeNanos,
        windowStartElapsedRealtimeNanos?.let { elapsedRealtimeNanos - it } == window.observedDurationNanos,
    ).all { it }

    private suspend fun steps(parent: String, keys: List<MeasuredHistoryCandidate>, range: HistoryRange,
        problems: MutableSet<HistoryProblem>): Map<HistoryPosition, UnifiedHistoryRow> {
        // Same original loader/checksum/epoch/deletion fences, followed by selected-window retention.
        val read = ImportedStepsRetainedReader(database).readEntriesForRetentionInTransaction(listOf(parent))
        val entry = when (read) {
            is ImportedStepsRetainedRead.Ready -> read.entries.singleOrNull()
            is ImportedStepsRetainedRead.Unverifiable -> {
                if (read.reason !in setOf(ImportedStepsReadFailure.RETENTION, ImportedStepsReadFailure.DELETION,
                        ImportedStepsReadFailure.MISSING)) {
                    problems += HistoryProblem(HistorySource.STEPS,
                    if (read.reason == ImportedStepsReadFailure.DEPENDENCY_OVERFLOW)
                        {
                        HistoryProblemReason.DEPENDENCY_OVERFLOW
                    } else {
                        HistoryProblemReason.INTEGRITY_UNAVAILABLE
                    })
                }
                null
            }
        } ?: return emptyMap()
        return selectedSteps(entry, keys, range)
    }

    private suspend fun selectedSteps(entry: RetainedImportedStepsEntry, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange): Map<HistoryPosition, UnifiedHistoryRow> {
        val floor = requireNotNull(database.sourceEvidenceStateDao().get()).retainedFromMs
        val accepted = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        for ((runId, run) in entry.portableRunsById) {
            for (fact in run.facts) {
                val stored = entry.factsByRun.getValue(runId).single { it.logicalFactId == fact.identity.value }
                val key = keys.singleOrNull { it.identity == framed(entry.metadata.identity, runId,
                    fact.identity.value, stored.semanticRevision.toString()) } ?: continue
                check(key.factRevision == stored.semanticRevision && key.factId == fact.identity.value)
                check(key.startTimeMs == fact.intervalStartTimeMs && key.endTimeMs == fact.intervalEndTimeMs)
                if (!overlaps(key, range) || fact.precedesRetainedFloor(floor, entry, runId)) {
                    continue
                }
                val history = fact.measuredHistory()
                val position = measuredHistoryPosition(key)
                accepted[position] = row(key, HistoryMeasurement.Steps(history))
            }
        }
        return accepted
    }

    private fun PortableStepsFactV1.precedesRetainedFloor(
        floor: Long?,
        entry: RetainedImportedStepsEntry,
        runId: String,
    ): Boolean = floor?.let {
        intervalEndTimeMs < it ||
            (intervalStartTimeMs < it && runId !in entry.retentionTruncatedRunIds)
    } == true

    // Projects the four portable coverage states to the existing public Steps value and status fields.
    @Suppress("CyclomaticComplexMethod")
    private fun PortableStepsFactV1.measuredHistory(): StepsHistory {
        val count = if (coverage == PortableStepsFactCoverage.COVERED) {
            requireNotNull(stepCount)
        } else {
            null
        }
        val cause = when (coverage) {
            PortableStepsFactCoverage.BASELINE -> StepsHistoryCause.BASELINE_ONLY
            PortableStepsFactCoverage.RESET_GAP, PortableStepsFactCoverage.PARTIAL ->
                StepsHistoryCause.PROVIDER_GAP
            PortableStepsFactCoverage.COVERED -> null
        }
        return StepsHistory(count, HistoryAvailability.RETAINED_IMPORTED,
            when { count == null -> HistoryEvidence.NONE; count == 0L -> HistoryEvidence.ACTIVE
                else -> HistoryEvidence.RECORDED },
            if (count == null) {
                HistoryProductState.PARTIAL
            } else {
                HistoryProductState.READY
            },
            if (count == null) {
                StepsHistoryCoverage.UNKNOWN
            } else {
                StepsHistoryCoverage.COMPLETE
            },
            if (cause == null) {
                emptySet()
            } else {
                setOf(cause)
            })
    }

    private fun row(key: MeasuredHistoryCandidate, measurement: HistoryMeasurement) = UnifiedHistoryRow(
        position = measuredHistoryPosition(key), toExclusiveMs = key.endTimeMs,
        origins = setOf(HistoryOrigin.IMPORTED), purpose = HistoryPurpose.CAPTURE,
        sections = emptyList(), measurement = measurement,
    )

    private fun overlaps(key: MeasuredHistoryCandidate, range: HistoryRange): Boolean =
        key.startTimeMs < range.toExclusiveMs && requireNotNull(key.endTimeMs).let {
            if (key.kind == "imported_activity") {
                it >= range.fromInclusiveMs || key.startTimeMs >= range.fromInclusiveMs
            }
            else {
                it > range.fromInclusiveMs || it == key.startTimeMs && it >= range.fromInclusiveMs
            }
        }

    private fun earliest(time: Long, uncertainty: Long): Long = if (uncertainty >= time) {
        0L
    } else {
        time - uncertainty
    }

    private fun framed(vararg values: String) = values.joinToString("") { "${it.length}:$it" }

    private fun leafSql(source: HistorySource): String = when (source) {
        HistorySource.ACTIVITY -> """
            SELECT 'imported_activity' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
                ${sqlFrame("entry.identity", "entry.import_revision", "fragment.run_identity",
                    "fragment.window_identity", "fragment.ordinal")} AS identity,
                fragment.start_wall_time_ms AS startTimeMs, fragment.end_wall_time_ms AS endTimeMs,
                NULL AS rowId, entry.identity AS writerId, NULL AS writerVersion,
                fragment.window_identity AS factId, entry.import_revision AS factRevision, fragment.ordinal AS itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity
            FROM imported_activity_fragment fragment JOIN imported_activity_entry_revision entry
                ON entry.identity = fragment.entry_identity AND entry.import_revision = fragment.entry_import_revision
            WHERE fragment.fragment_kind = 'BAND' AND fragment.start_wall_time_ms IS NOT NULL
                AND fragment.end_wall_time_ms IS NOT NULL AND entry.import_revision =
                    (SELECT MAX(latest.import_revision) FROM imported_activity_entry_revision latest WHERE latest.identity = entry.identity)
        """.trimIndent()
        HistorySource.PRESSURE -> """
            SELECT 'imported_pressure' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
                ${sqlFrame("entry.identity", "entry.import_revision", "window.run_identity",
                    "window.identity")} AS identity,
                window.interval_start_time_ms AS startTimeMs, window.interval_end_time_ms AS endTimeMs,
                NULL AS rowId, entry.identity AS writerId, NULL AS writerVersion,
                window.identity AS factId, entry.import_revision AS factRevision, NULL AS itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity
            FROM imported_pressure_window window JOIN imported_pressure_entry_revision entry
                ON entry.identity = window.entry_identity AND entry.import_revision = window.entry_import_revision
            WHERE entry.import_revision =
                (SELECT MAX(latest.import_revision) FROM imported_pressure_entry_revision latest WHERE latest.identity = entry.identity)
        """.trimIndent()
        HistorySource.STEPS -> """
            SELECT 'imported_steps' AS kind, 'IMPORTED' AS origin, 'CAPTURE' AS purpose,
                ${sqlFrame("entry.identity", "run.identity", "fact.logical_fact_id",
                    "fact.semantic_revision")} AS identity,
                fact.interval_start_time_ms AS startTimeMs, fact.interval_end_time_ms AS endTimeMs,
                NULL AS rowId, entry.identity AS writerId, NULL AS writerVersion,
                fact.logical_fact_id AS factId, fact.semantic_revision AS factRevision, NULL AS itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity
            FROM step_fact_revision fact JOIN imported_steps_run run ON run.identity = fact.service_run_id
                JOIN imported_steps_entry entry ON entry.identity = run.entry_identity
            WHERE fact.origin_kind = 'PORTABLE_IMPORT' AND fact.operation = 'UPSERT'
                AND fact.semantic_revision = (SELECT MAX(latest.semantic_revision) FROM step_fact_revision latest
                    WHERE latest.writer_projection_id = fact.writer_projection_id
                        AND latest.writer_projection_version = fact.writer_projection_version
                        AND latest.logical_fact_id = fact.logical_fact_id)
        """.trimIndent()
        else -> error("Unsupported imported measured source")
    }

    private fun sqlFrame(vararg values: String): String = values.joinToString(" || ") {
        "length(CAST($it AS TEXT)) || ':' || CAST($it AS TEXT)"
    }
}

private const val MAX_MEASURED_CANDIDATES = 100
