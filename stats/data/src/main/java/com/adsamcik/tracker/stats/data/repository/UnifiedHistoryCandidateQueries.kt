package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryRange

/** No user-supplied SQL identifiers. Source readers still authenticate every selected seed. */
internal object UnifiedHistoryCandidateQueries {
    private val native = """
        SELECT 'native' AS category, 'logical:' || l.logical_tracking_id AS identity,
            l.started_at_ms AS startTimeMs, l.cutoff_at_ms AS endTimeMs,
            NULL AS zoneId, NULL AS epochDay,
            (SELECT MIN(r.session_segment_id) FROM source_service_run r
                WHERE r.logical_tracking_id = l.logical_tracking_id) AS segmentId, 0 AS candidateConflict
        FROM logical_tracking_session l
        WHERE EXISTS (SELECT 1 FROM session_manifest_version m JOIN session_manifest_source s
            ON s.logical_tracking_id = m.logical_tracking_id AND s.manifest_revision = m.manifest_revision
            WHERE m.logical_tracking_id = l.logical_tracking_id AND s.purpose = 'SESSION_CAPTURE'
            AND s.persistence_eligible = 1)
        UNION ALL
        SELECT 'legacy', 'segment:' || CAST(s.id AS TEXT), s.start_time_ms, s.end_time_ms,
            NULL, NULL, s.id, 0 FROM session_segment s
        WHERE s.logical_tracking_id IS NULL AND s.service_run_id IS NULL
    """.trimIndent()

    fun native(range: HistoryRange, after: HistoryPosition?) = page(native, "local", range, after)

    fun imported(source: String, range: HistoryRange, after: HistoryPosition?): SimpleSQLiteQuery {
        require(source in setOf("wifi", "cell", "activity", "pressure", "steps"))
        val table = if (source == "steps") "imported_steps_entry" else "imported_${source}_entry_revision"
        val latest = if (source == "steps") "" else
            "WHERE e.import_revision = (SELECT MAX(n.import_revision) FROM $table n WHERE n.identity = e.identity)"
        val conflict = if (source in setOf("activity", "pressure"))
            "EXISTS(SELECT 1 FROM imported_${source}_retention_receipt r WHERE r.entry_identity = e.identity)"
            else "0"
        val live = """SELECT '$source' AS category, e.identity, e.start_time_ms AS startTimeMs,
            e.end_time_ms AS endTimeMs, NULL AS zoneId, NULL AS epochDay, NULL AS segmentId, $conflict AS candidateConflict
            FROM $table e $latest"""
        val retained = when (source) {
            "activity", "pressure" -> """ UNION ALL SELECT '$source', entry_identity, start_time_ms,
                end_time_ms, NULL, NULL, NULL, 0 FROM imported_${source}_retention_receipt r
                WHERE NOT EXISTS(SELECT 1 FROM $table e WHERE e.identity = r.entry_identity)"""
            else -> ""
        }
        return page(live + retained, "imported:$source", range, after)
    }

    private fun page(relation: String, origin: String, range: HistoryRange, after: HistoryPosition?): SimpleSQLiteQuery {
        val seek = if (after == null) "" else
            "AND (startTimeMs < ? OR (startTimeMs = ? AND (? > ? OR (? = ? AND identity > ?))))"
        val bindings = mutableListOf<Any?>(range.toExclusiveMs, range.fromInclusiveMs, range.fromInclusiveMs, range.fromInclusiveMs)
        if (after != null) bindings.addAll(listOf(after.startTimeMs, after.startTimeMs,
            origin, after.originTag, origin, after.originTag, after.stableId))
        return SimpleSQLiteQuery("""SELECT *, (endTimeMs IS NOT NULL AND endTimeMs < startTimeMs) AS temporalUncertain FROM ($relation)
            WHERE startTimeMs >= 0 AND startTimeMs < ?
            AND (endTimeMs IS NULL OR endTimeMs > ? OR (endTimeMs = startTimeMs AND startTimeMs >= ?) OR (endTimeMs < startTimeMs AND startTimeMs >= ?))
            $seek ORDER BY startTimeMs DESC, identity ASC LIMIT 101""", bindings.toTypedArray())
    }
}
