package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.MeasuredHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.shared.base.database.data.AllDaySensorObservationIdentity
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

/** Native timestamped observations share bounded paging with released rows; neither needs a session. */
internal class MeasuredLegacySensorHistoryReader(private val database: AppDatabase) {
    suspend fun candidates(source: HistorySource, range: HistoryRange,
        after: HistoryPosition?): List<MeasuredHistoryCandidate> {
        require(source == HistorySource.ACTIVITY || source == HistorySource.PRESSURE)
        if (source !in range.sources || range.origins.isEmpty() || range.purposes.isEmpty()) {
            return emptyList()
        }
        val activity = source == HistorySource.ACTIVITY
        val legacyKind = "${source.name.lowercase()}_legacy"
        val nativeKind = "${source.name.lowercase()}_observation"
        val table = if (activity) { "activity_snapshot" } else { "pressure_sample" }
        val prefix = if (activity) {
            AllDaySensorObservationIdentity.ACTIVITY_PREFIX
        } else {
            AllDaySensorObservationIdentity.PRESSURE_PREFIX
        }
        val allDay = "source_signal_id LIKE '$prefix%' AND length(source_signal_id) > ${prefix.length}"
        val imported = "instr(',' || COALESCE(capability_flags, '') || ',', ',PORTABLE_IMPORT,') > 0"
        val seek = if (after == null) { "" } else { """AND (startTimeMs < ? OR (startTimeMs = ? AND
            (lower(origin) || ':' || kind > ? OR (lower(origin) || ':' || kind = ? AND identity > ?))))""" }
        val origins = range.origins.map { it.name }
        val purposes = range.purposes.map { it.name }
        val args = mutableListOf<Any?>(range.fromInclusiveMs, range.toExclusiveMs)
        args.addAll(origins)
        args.addAll(purposes)
        if (after != null) {
            args.addAll(listOf(after.startTimeMs, after.startTimeMs, after.originTag, after.originTag, after.stableId))
        }
        return database.trackingHistoryReadDao().measuredHistoryCandidates(SimpleSQLiteQuery("""
            SELECT kind, origin, purpose, identity, startTimeMs, endTimeMs, rowId,
                writerId, writerVersion, factId, factRevision, itemIndex,
                NULL AS physicalEventId, NULL AS physicalIdentity FROM (
            SELECT CASE WHEN $allDay THEN '$nativeKind' ELSE '$legacyKind' END AS kind,
                CASE WHEN $allDay THEN CASE WHEN $imported THEN 'IMPORTED' ELSE 'LOCAL' END
                    ELSE 'LEGACY' END AS origin,
                CASE WHEN $allDay THEN 'BACKGROUND' ELSE 'UNKNOWN' END AS purpose,
                CASE WHEN $allDay THEN source_signal_id
                    ELSE '$legacyKind:' || printf('%020d', id) END AS identity,
                time_ms AS startTimeMs, NULL AS endTimeMs, id AS rowId,
                NULL AS writerId, NULL AS writerVersion, NULL AS factId,
                NULL AS factRevision, NULL AS itemIndex
            FROM $table WHERE time_ms >= ? AND time_ms < ?)
            WHERE origin IN (${origins.joinToString { "?" }}) AND purpose IN (${purposes.joinToString { "?" }})
                $seek ORDER BY startTimeMs DESC, lower(origin) || ':' || kind, identity LIMIT 101
            """, args.toTypedArray()))
    }

    /** The outer facade owns the Room snapshot; neither discovery nor qualification starts a sensor. */
    suspend fun qualify(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange): MeasuredLegacySensorHistoryRead {
        requireLegacyCandidates(source, keys)
        if (keys.isEmpty() || source !in range.sources) {
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
        val legacyKind = "${source.name.lowercase()}_legacy"
        val nativeKind = "${source.name.lowercase()}_observation"
        require(keys.size <= MAX_MEASURED_CANDIDATES && keys.all {
            it.kind == legacyKind && it.origin == "LEGACY" && it.purpose == "UNKNOWN" ||
                it.kind == nativeKind && it.origin in setOf("LOCAL", "IMPORTED") && it.purpose == "BACKGROUND"
        })
    }

    private fun composeLegacyRows(source: HistorySource, keys: List<MeasuredHistoryCandidate>,
        range: HistoryRange, retainedFromMs: Long?, activity: Map<Long, ActivitySnapshot>,
        pressure: Map<Long, PressureSample>): MeasuredLegacySensorHistoryRead {
        val rows = linkedMapOf<HistoryPosition, UnifiedHistoryRow>()
        val problems = linkedSetOf<HistoryProblem>()
        for (key in keys) {
            if (!key.matchesSelection(range, retainedFromMs)) {
                continue
            }
            val origin = HistoryOrigin.valueOf(key.origin)
            val purpose = HistoryPurpose.valueOf(key.purpose)
            if (!savedIdentityMatches(source, key, activity, pressure)) {
                problems += HistoryProblem(source, HistoryProblemReason.INTEGRITY_UNAVAILABLE)
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
            rows[position] = UnifiedHistoryRow(position, null, setOf(origin),
                purpose, sections = emptyList(), measurement = measurement)
        }
        return MeasuredLegacySensorHistoryRead(rows, problems)
    }

    private fun MeasuredHistoryCandidate.matchesSelection(range: HistoryRange, floor: Long?): Boolean =
        !outsideRange(range, floor) && HistoryOrigin.valueOf(origin) in range.origins &&
            HistoryPurpose.valueOf(purpose) in range.purposes

    private fun MeasuredHistoryCandidate.outsideRange(range: HistoryRange, floor: Long?): Boolean =
        startTimeMs !in range.fromInclusiveMs until range.toExclusiveMs || floor?.let { startTimeMs < it } == true

    private fun savedIdentityMatches(source: HistorySource, key: MeasuredHistoryCandidate,
        activity: Map<Long, ActivitySnapshot>, pressure: Map<Long, PressureSample>): Boolean {
        val identity = if (source == HistorySource.ACTIVITY) {
            activity[key.rowId]?.sourceSignalId
        } else {
            pressure[key.rowId]?.sourceSignalId
        }
        val flags = if (source == HistorySource.ACTIVITY) {
            activity[key.rowId]?.observationStamp?.capabilityFlags
        } else {
            pressure[key.rowId]?.observationStamp?.capabilityFlags
        }
        val allDay = if (source == HistorySource.ACTIVITY) {
            AllDaySensorObservationIdentity.activityEventId(identity) != null
        } else {
            AllDaySensorObservationIdentity.pressureEventId(identity) != null
        }
        val imported = AllDaySensorObservationIdentity.IMPORTED_CAPABILITY in flags.orEmpty().split(',')
        val origin = if (imported) { HistoryOrigin.IMPORTED } else { HistoryOrigin.LOCAL }
        return allDay == (key.purpose == HistoryPurpose.BACKGROUND.name) &&
            (!allDay || key.identity == identity && key.origin == origin.name)
    }

    private fun ActivitySnapshot?.toActivityMeasurement(expectedTimeMs: Long): HistoryMeasurement.LegacyActivity? =
        this?.takeIf { it.timeMs == expectedTimeMs && it.confidence in 0..100 }
            ?.let { HistoryMeasurement.LegacyActivity(it.activityType, it.confidence, it.isTransition,
                it.observationStamp.capabilityFlags.orEmpty().split(',')
                    .firstOrNull { flag -> flag.startsWith("ACTIVITY_TRANSITION:") }
                    ?.removePrefix("ACTIVITY_TRANSITION:")?.toIntOrNull()?.takeIf { type -> type in 0..1 }) }

    private fun PressureSample?.toPressureMeasurement(expectedTimeMs: Long): HistoryMeasurement.LegacyPressure? =
        this?.takeIf {
            it.timeMs == expectedTimeMs && it.pressureHpa.isFinite() && it.pressureHpa > 0f && it.sampleCount > 0
        }
            ?.let { sample -> HistoryMeasurement.LegacyPressure(sample.pressureHpa, sample.sampleCount,
                sample.minPressureHpa?.takeIf { it.isFinite() && it > 0f },
                sample.maxPressureHpa?.takeIf { it.isFinite() && it > 0f },
                sample.standardDeviationHpa?.takeIf { it.isFinite() && it >= 0f },
                sample.altitudeM.takeIf { it.isFinite() &&
                    AllDaySensorObservationIdentity.UNKNOWN_ALTITUDE_CAPABILITY !in
                        sample.observationStamp.capabilityFlags.orEmpty().split(',') },
                sample.windowStartElapsedRealtimeNanos,
                sample.windowEndElapsedRealtimeNanos, isPartial = sample.observationStamp.capabilityFlags
                    .orEmpty().split(',').firstOrNull { it.startsWith("PRESSURE_QUALIFICATION:") }
                    ?.removePrefix("PRESSURE_QUALIFICATION:")?.let { it != "COMPLETE" } == true) }
}

internal data class MeasuredLegacySensorHistoryRead(
    val rows: Map<HistoryPosition, UnifiedHistoryRow> = emptyMap(),
    val problems: Set<HistoryProblem> = emptySet(),
)

private const val MAX_MEASURED_CANDIDATES = 100
