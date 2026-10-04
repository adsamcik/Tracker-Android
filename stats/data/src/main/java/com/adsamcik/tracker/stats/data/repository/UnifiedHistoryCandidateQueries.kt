package com.adsamcik.tracker.stats.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import com.adsamcik.tracker.stats.api.repository.HistoryPosition
import com.adsamcik.tracker.stats.api.repository.HistoryRange
import com.adsamcik.tracker.stats.api.repository.HistoryOrigin
import com.adsamcik.tracker.shared.base.database.dao.LocationReadScope

/** No user-supplied SQL identifiers. Source readers still authenticate every selected seed. */
internal object UnifiedHistoryCandidateQueries {
    /** One key-only probe skips the remainder of a displayed day, regardless of its reading count. */
    fun backgroundObservationBefore(source: String, range: HistoryRange, beforeExclusiveMs: Long): SimpleSQLiteQuery {
        require(source in setOf("location", "wifi", "cell"))
        require(beforeExclusiveMs > range.fromInclusiveMs && beforeExclusiveMs <= range.toExclusiveMs)
        val relation = if (source == "location") sessionlessLocationRelation(range.origins)
            else radioObservationRelation(source, range.origins)
        return SimpleSQLiteQuery("""SELECT '$source' AS category, '$source' AS identity,
            MIN(endTimeMs - 1, ?3) AS startTimeMs, NULL AS endTimeMs, NULL AS zoneId,
            NULL AS epochDay, NULL AS segmentId, 0 AS candidateConflict, 0 AS temporalUncertain
            FROM ($relation) WHERE startTimeMs >= 0 AND startTimeMs < ?2 AND endTimeMs > ?1
            ORDER BY startTimeMs DESC LIMIT 1""",
            arrayOf(range.fromInclusiveMs, beforeExclusiveMs, beforeExclusiveMs - 1))
    }

    private fun sessionlessLocationRelation(origins: Set<HistoryOrigin>): String {
        val included = origins.intersect(setOf(HistoryOrigin.LOCAL, HistoryOrigin.IMPORTED, HistoryOrigin.LEGACY))
        if (included.isEmpty()) return emptyObservationRelation
        val names = included.joinToString(",") { "'${it.name}'" }
        return """SELECT * FROM (SELECT location_sample.time_ms AS startTimeMs,
            CASE WHEN location_sample.time_ms = 9223372036854775807 THEN location_sample.time_ms
                ELSE location_sample.time_ms + 1 END AS endTimeMs
            FROM location_sample WHERE location_sample.time_ms >= ?1 AND location_sample.time_ms < ?2
            AND ${LocationReadScope.HISTORY_RETAINED}
            AND ${LocationReadScope.SESSIONLESS_HISTORY} AND ${LocationReadScope.HISTORY_ORIGIN} IN ($names)
            AND location_sample.lat_e7 BETWEEN -900000000 AND 900000000
            AND location_sample.lon_e7 BETWEEN -1800000000 AND 1800000000
            ORDER BY location_sample.time_ms DESC LIMIT 1)"""
    }

    private fun radioObservationRelation(source: String, origins: Set<HistoryOrigin>): String {
        val relations = mutableListOf<String>()
        fun point(table: String, time: String, joins: String = "", predicate: String = "") =
            "SELECT * FROM (SELECT $time AS startTimeMs, CASE WHEN $time = 9223372036854775807 THEN $time ELSE $time + 1 END AS endTimeMs FROM $table $joins WHERE $time >= ?1 AND $time < ?2 $predicate ORDER BY $time DESC LIMIT 1)"
        fun gap(table: String, start: String, end: String) =
            "SELECT * FROM (SELECT $start AS startTimeMs, $end AS endTimeMs FROM $table WHERE $start < ?2 AND $end > ?1 ORDER BY MIN($end - 1, ?3) DESC LIMIT 1)"
        if (HistoryOrigin.LOCAL in origins) {
            val current = "JOIN ambient_${source}_fact_cursor c ON c.writer_id = f.writer_id AND c.writer_version = f.writer_version AND c.logical_fact_id = f.logical_fact_id AND c.latest_semantic_revision = f.semantic_revision"
            relations += point("ambient_${source}_fact_revision f", "f.observed_wall_time_ms", current)
            relations += point("ambient_${source}_child n", "n.observed_wall_time_ms",
                "JOIN ambient_${source}_fact_cursor c ON c.writer_id = n.writer_id AND c.writer_version = n.writer_version AND c.logical_fact_id = n.logical_fact_id AND c.latest_semantic_revision = n.semantic_revision")
            relations += gap("ambient_${source}_gap", "gap_start_time_ms", "gap_end_time_ms")
        }
        if (HistoryOrigin.IMPORTED in origins) {
            val latest = "AND f.semantic_revision = (SELECT MAX(n.semantic_revision) FROM imported_ambient_${source}_fact n WHERE n.fact_id = f.fact_id)"
            relations += point("imported_ambient_${source}_fact f", "f.observed_time_ms", predicate = latest)
            relations += point("imported_ambient_${source}_child c", "c.observed_wall_time_ms",
                "JOIN imported_ambient_${source}_fact f ON f.archive_id = c.archive_id AND f.fact_id = c.fact_id AND f.semantic_revision = c.semantic_revision", latest)
            relations += gap("imported_ambient_${source}_gap", "start_time_ms", "end_time_ms")
        }
        return relations.joinToString(" UNION ALL ").ifEmpty { emptyObservationRelation }
    }

    private const val emptyObservationRelation = "SELECT 0 AS startTimeMs, 0 AS endTimeMs WHERE 0"
    private val native = """
        SELECT 'native' AS category, 'logical:' || l.logical_tracking_id AS identity,
            COALESCE(a.start_time_ms, l.started_at_ms) AS startTimeMs,
            COALESCE(a.end_time_ms, l.cutoff_at_ms) AS endTimeMs,
            NULL AS zoneId, NULL AS epochDay,
            (SELECT MIN(r.session_segment_id) FROM source_service_run r
                WHERE r.logical_tracking_id = l.logical_tracking_id) AS segmentId, 0 AS candidateConflict
        FROM logical_tracking_session l
        LEFT JOIN organization_annotation a ON a.recording_adapter_key = 'logical:' || l.logical_tracking_id
        WHERE COALESCE(a.hidden, 0) = 0 AND EXISTS (SELECT 1 FROM session_manifest_version m JOIN session_manifest_source s
            ON s.logical_tracking_id = m.logical_tracking_id AND s.manifest_revision = m.manifest_revision
            WHERE m.logical_tracking_id = l.logical_tracking_id AND s.purpose = 'SESSION_CAPTURE'
            AND s.persistence_eligible = 1)
        UNION ALL
        SELECT 'legacy', 'segment:' || CAST(s.id AS TEXT),
            COALESCE(a.start_time_ms, s.start_time_ms), COALESCE(a.end_time_ms, s.end_time_ms),
            NULL, NULL, s.id, 0 FROM session_segment s
        LEFT JOIN organization_annotation a ON a.recording_adapter_key = 'segment:' || s.id
        WHERE s.logical_tracking_id IS NULL AND s.service_run_id IS NULL AND COALESCE(a.hidden, 0) = 0
    """.trimIndent()

    fun native(range: HistoryRange, after: HistoryPosition?) = page(native, "local", range, after)

    /** Duplicate normalization checks only exact qualified collision owners, beyond page heads. */
    fun nativeVisibility(range: HistoryRange, logicalIds: List<String>): SimpleSQLiteQuery {
        require(logicalIds.size in 1..100 && logicalIds.distinct().size == logicalIds.size)
        val placeholders = logicalIds.joinToString(",") { "?" }
        return SimpleSQLiteQuery("""SELECT *, (endTimeMs IS NOT NULL AND endTimeMs < startTimeMs) AS temporalUncertain
            FROM ($native) WHERE category = 'native' AND identity IN ($placeholders)
            AND startTimeMs >= 0 AND startTimeMs < ?
            AND (endTimeMs IS NULL OR endTimeMs > ? OR
                (endTimeMs <= startTimeMs AND startTimeMs >= ?))
            ORDER BY startTimeMs DESC, identity ASC LIMIT 101""",
            (logicalIds.map { "logical:$it" } + listOf(range.toExclusiveMs, range.fromInclusiveMs,
                range.fromInclusiveMs)).toTypedArray())
    }

    fun organization(range: HistoryRange, after: HistoryPosition?) = page("""
        SELECT 'organization' AS category, 'annotation:' || local_id AS identity,
            start_time_ms AS startTimeMs, end_time_ms AS endTimeMs, display_zone_id AS zoneId,
            NULL AS epochDay, NULL AS segmentId, 0 AS candidateConflict
        FROM organization_annotation WHERE recording_adapter_key IS NULL AND hidden = 0
    """.trimIndent(), "organization", range, after)

    /** Zone discovery is key-only; no sensor payload is read or inferred from a UTC calendar. */
    fun backgroundZones(source: String, range: HistoryRange): SimpleSQLiteQuery {
        val relation = backgroundRelation(source, range.origins)
        return SimpleSQLiteQuery("""SELECT DISTINCT zoneId FROM ($relation)
            WHERE zoneId IS NOT NULL AND epochDay IS NOT NULL AND startTimeMs < ?
                AND endTimeMs > ? ORDER BY zoneId LIMIT 101""",
            arrayOf(range.toExclusiveMs, range.fromInclusiveMs))
    }

    /** Epoch-day order is exact within one stored zone, including short and long DST days. */
    fun backgroundDays(source: String, range: HistoryRange, zoneId: String,
        firstEpochDay: Long, lastEpochDay: Long, afterEpochDay: Long?): SimpleSQLiteQuery {
        val relation = backgroundRelation(source, range.origins)
        return SimpleSQLiteQuery("""SELECT '$source' AS category,
            CAST(epochDay AS TEXT) || ':' || zoneId AS identity,
            MIN(startTimeMs) AS startTimeMs, MAX(endTimeMs) AS endTimeMs,
            zoneId, epochDay, NULL AS segmentId, 0 AS candidateConflict, 0 AS temporalUncertain
            FROM ($relation) WHERE zoneId = ? AND epochDay BETWEEN ? AND ?
                AND (? IS NULL OR epochDay <= ?) GROUP BY epochDay, zoneId
            ORDER BY epochDay DESC LIMIT 101""",
            arrayOf(zoneId, firstEpochDay, lastEpochDay, afterEpochDay, afterEpochDay))
    }

    private fun backgroundRelation(source: String, origins: Set<HistoryOrigin>): String {
        require(source in setOf("steps", "wifi", "cell"))
        val relations = mutableListOf<String>()
        if (HistoryOrigin.LOCAL in origins) {
            relations += """SELECT structural_epoch_day AS epochDay, stored_zone_id AS zoneId,
                structural_day_start_time_ms AS startTimeMs, structural_day_end_time_ms AS endTimeMs
                FROM ambient_${source}_fact_revision"""
            if (source != "steps") relations += """SELECT structural_epoch_day AS epochDay, stored_zone_id AS zoneId,
                gap_start_time_ms AS startTimeMs, gap_end_time_ms AS endTimeMs FROM ambient_${source}_gap"""
        }
        if (HistoryOrigin.IMPORTED in origins) {
            if (source == "steps") {
                relations += """SELECT structural_epoch_day AS epochDay, stored_zone_id AS zoneId,
                    structural_day_start_time_ms AS startTimeMs, structural_day_end_time_ms AS endTimeMs
                    FROM imported_ambient_steps_day_revision"""
                relations += """SELECT structural_epoch_day AS epochDay, stored_zone_id AS zoneId,
                    structural_day_start_time_ms AS startTimeMs, structural_day_end_time_ms AS endTimeMs
                    FROM imported_ambient_steps_day_fence"""
            } else {
                relations += """SELECT structural_epoch_day AS epochDay, stored_zone_id AS zoneId,
                    coverage_start_time_ms AS startTimeMs,
                    CASE WHEN latest_possible_time_ms = 9223372036854775807 THEN latest_possible_time_ms
                        ELSE latest_possible_time_ms + 1 END AS endTimeMs
                    FROM imported_ambient_${source}_fact"""
                relations += """SELECT structural_epoch_day AS epochDay, stored_zone_id AS zoneId,
                    start_time_ms AS startTimeMs, end_time_ms AS endTimeMs FROM imported_ambient_${source}_gap"""
            }
        }
        return relations.joinToString(" UNION ALL ").ifEmpty {
            "SELECT NULL AS epochDay, NULL AS zoneId, 0 AS startTimeMs, 0 AS endTimeMs WHERE 0"
        }
    }

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
