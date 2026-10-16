package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.PressureSample
import com.adsamcik.tracker.stats.api.repository.HistoryMeasurement
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryProblem
import com.adsamcik.tracker.stats.api.repository.HistoryProblemReason
import com.adsamcik.tracker.stats.api.repository.HistoryPurpose
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.UnifiedHistoryRow

/** Released observations use their own saved timestamp, with no inferred session or duration. */
internal class MeasuredLegacySensorHistoryReader(private val database: AppDatabase) {
    suspend fun candidates(source: HistorySource, range: HistoryRange,
        after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        require(source == HistorySource.ACTIVITY || source == HistorySource.PRESSURE)
        if (source !in range.sources || HistoryOrigin.LEGACY !in range.origins ||
            HistoryPurpose.UNKNOWN !in range.purposes) {
            return emptyList()
        }
        val kind = if (source == HistorySource.ACTIVITY) {
            "activity_legacy"
        } else {
            "pressure_legacy"
        }
        val table = if (source == HistorySource.ACTIVITY) {
            "activity_snapshot"
        } else {
            "pressure_sample"
        }
        val tag = "legacy:$kind"
        val seek = if (after == null) {
            ""
        } else {
            """AND (startTimeMs < ? OR (startTimeMs = ? AND
            (? > ? OR (? = ? AND identity > ?))))"""
        }
        val args = mutableListOf<Any?>(range.fromInclusiveMs, range.toExclusiveMs)
        if (after != null) {
            args.addAll(listOf(after.startTimeMs, after.startTimeMs, tag, after.originTag,
            tag, after.originTag, after.stableId))
        }
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery("""
            SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId,
                writerId, writerVersion, factId, factRevision, itemIndex, NULL AS physicalEventId, NULL AS physicalIdentity FROM (
            SELECT '$kind' AS kind, 'LEGACY' AS origin, 'UNKNOWN' AS purpose,
                '$kind:' || printf('%020d', id) AS identity, time_ms AS startTimeMs,
                NULL AS endTimeMs, id AS rowId, NULL AS writerId, NULL AS writerVersion,
                NULL AS factId, NULL AS factRevision, NULL AS itemIndex
            FROM $table WHERE time_ms >= ? AND time_ms < ?)
            WHERE 1 $seek ORDER BY startTimeMs DESC, identity LIMIT 101
            """, args.toTypedArray()))
    }

    /** The outer facade owns the Room snapshot; neither discovery nor qualification starts a sensor. */
    suspend fun qualify(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredLegacySensorHistoryRead {
        requireLegacyCandidates(source, keys)
        if (keys.isEmpty() || source !in range.sources || HistoryOrigin.LEGACY !in range.origins ||
            HistoryPurpose.UNKNOWN !in range.purposes) {
            return MeasuredLegacySensorHistoryRead()
        }
        val state = database.sourceEvidenceStateDao().get() ?: return MeasuredLegacySensorHistoryRead(
            problems = setOf(HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)))
        val ids = keys.map { requireNotNull(it.rowId) }
        require(ids.size == ids.distinct().size)
        val activity = if (source == HistorySource.ACTIVITY) {
            database.activitySnapshotDao().historyRows(ids)
            .associateBy { it.id }
        } else {
            emptyMap()
        }
        val pressure = if (source == HistorySource.PRESSURE) {
            database.pressureSampleDao().historyRows(ids)
            .associateBy { it.id }
        } else {
            emptyMap()
        }
        return composeLegacyRows(source, keys, range, state.retainedFromMs, activity, pressure)
    }

    private fun requireLegacyCandidates(source: HistorySource, keys: List<MeasuredHistoryCandidate>) {
        require(source == HistorySource.ACTIVITY || source == HistorySource.PRESSURE)
        val kind = if (source == HistorySource.ACTIVITY) {
            "activity_legacy"
        } else {
            "pressure_legacy"
        }
        require(keys.size <= MAX_MEASURED_CANDIDATES &&
            keys.all { it.kind == kind && it.origin == "LEGACY" && it.purpose == "UNKNOWN" })
    }

    private fun composeLegacyRows(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange, retainedFromMs: Long?, activity: Map<Long, ActivitySnapshot>,
        pressure: Map<Long, PressureSample>): MeasuredLegacySensorHistoryRead {
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = linkedSetOf<HistoryProblem>()
        for (key in keys) {
            if (key.startTimeMs !in range.fromInclusiveMs until range.toExclusiveMs ||
                retainedFromMs?.let { key.startTimeMs < it } == true) {
                continue
            }
            val measurement = if (source == HistorySource.ACTIVITY) {
                activity[key.rowId].toActivityMeasurement(key.startTimeMs)
            } else {
                pressure[key.rowId].toPressureMeasurement(key.startTimeMs)
            }
            if (measurement == null) {
                problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
                continue
            }
            val position = measuredHistoryPosition(key)
            rows[position] = UnifiedHistoryRow(position, null, setOf(HistoryOrigin.LEGACY),
                HistoryPurpose.UNKNOWN, sections = emptyList(), measurement = measurement)
        }
        return MeasuredLegacySensorHistoryRead(rows, problems)
    }

    private fun ActivitySnapshot?.toActivityMeasurement(expectedTimeMs: Long): HistoryMeasurement.LegacyActivity? =
        this?.takeIf { it.timeMs == expectedTimeMs && it.confidence in 0..100 }
            ?.let { HistoryMeasurement.LegacyActivity(it.activityType, it.confidence, it.isTransition) }

    private fun PressureSample?.toPressureMeasurement(expectedTimeMs: Long): HistoryMeasurement.LegacyPressure? =
        this?.takeIf {
            it.timeMs == expectedTimeMs && it.pressureHpa.isFinite() && it.pressureHpa > 0f && it.sampleCount > 0
        }
            ?.let { sample -> HistoryMeasurement.LegacyPressure(sample.pressureHpa, sample.sampleCount,
                sample.minPressureHpa?.takeIf { it.isFinite() && it > 0f },
                sample.maxPressureHpa?.takeIf { it.isFinite() && it > 0f },
                sample.standardDeviationHpa?.takeIf { it.isFinite() && it >= 0f },
                sample.altitudeM.takeIf { it.isFinite() }, sample.windowStartElapsedRealtimeNanos,
                sample.windowEndElapsedRealtimeNanos) }
}

internal data class MeasuredLegacySensorHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)

private const val MAX_MEASURED_CANDIDATES = 100
