package com.adsamcik.tracker.map.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * SafeQueryBuilder builds parameterized spatial/temporal raw queries against underlying
 * geo tables (location_sample, wifi_observation, cell_sample) restricting selectable columns
 * and predicates to a vetted allow-list to reduce SQL injection risk.
 *
 * Coordinates are stored as E7 integers (degrees × 1e7) and converted to degrees in the
 * SELECT projection. Bound parameters are converted from degrees to E7 for WHERE clauses.
 *
 * Usage:
 *  val query = SafeQueryBuilder.location()
 *      .bounds(north, east, south, west)
 *      .timeRange(start, end)
 *      .weight("speed")
 *      .build()
 */
class SafeQueryBuilder private constructor(
    private val table: Table,
) {
    private val selectColumns = linkedSetOf<String>()
    private var timeFrom: Long? = null
    private var timeTo: Long? = null
    private var north: Double? = null
    private var east: Double? = null
    private var south: Double? = null
    private var west: Double? = null
    private var limit: Int? = null
    private var sampleLimit: Int? = null
    private var newestLimit: Int? = null
    private var weightColumn: String? = null

    /**
     * Limit result rows (defensive cap for heavy queries)
     */
    fun limit(max: Int): SafeQueryBuilder = apply {
        require(max > 0) { "limit must be > 0" }
        require(sampleLimit == null && newestLimit == null) {
            "limit cannot be combined with sample or newest limits"
        }
        limit = max
    }

    /**
     * Uniformly sample an approximately bounded number of rows by stable auto-increment ID.
     * Filtering remains database-side and output stays ordered by time.
     */
    fun sample(max: Int): SafeQueryBuilder = apply {
        require(max > 0) { "sample limit must be > 0" }
        require(limit == null && newestLimit == null) {
            "sample cannot be combined with limit or newest"
        }
        sampleLimit = max
    }

    /**
     * Keep the newest bounded window while returning it in chronological order. This is used for
     * sequence-sensitive paths where uniform row sampling could manufacture false tracking gaps.
     */
    fun newest(max: Int): SafeQueryBuilder = apply {
        require(max > 0) { "newest limit must be > 0" }
        require(limit == null && sampleLimit == null) {
            "newest cannot be combined with limit or sample"
        }
        newestLimit = max
    }

    /**
     * Add columns to projection (always includes lat, lon, time implicitly).
     * Only columns from the allow list for the selected table are permitted.
     * Column names are public API names; they are mapped to actual DB columns internally.
     */
    fun columns(vararg cols: String): SafeQueryBuilder = apply {
        val allowed = table.allowedColumns
        cols.forEach { c ->
            require(allowed.contains(c)) { "Column '$c' not allowed for table ${table.tableName}" }
            selectColumns += c
        }
    }

    /**
     * Designate a numeric column to be returned as weight (aliased as weight).
     * Uses public API names; mapped to actual DB columns internally.
     */
    fun weight(column: String): SafeQueryBuilder = apply {
        require(table.allowedWeightColumns.contains(column)) { "Column '$column' not allowed as weight for ${table.tableName}" }
        weightColumn = column
    }

    /**
     * Set inclusive time range (epoch milliseconds).
     */
    fun timeRange(from: Long?, to: Long?): SafeQueryBuilder = apply {
        if (from != null && to != null) require(from <= to) { "from must be <= to" }
        timeFrom = from
        timeTo = to
    }

    /**
     * Spatial bounding box (lat/lon degrees). Order: north >= south, east >= west.
     */
    fun bounds(north: Double, east: Double, south: Double, west: Double): SafeQueryBuilder = apply {
        require(north >= south) { "north must be >= south" }
        require(east >= west) { "east must be >= west" }
        this.north = north
        this.east = east
        this.south = south
        this.west = west
    }

    fun build(): SupportSQLiteQuery {
        // All tables use E7 lat/lon and time_ms; convert to degrees in projection
        val baseCols = listOf(
            "CAST(lat_e7 AS REAL) / $E7_DIVISOR AS lat",
            "CAST(lon_e7 AS REAL) / $E7_DIVISOR AS lon",
            "time_ms AS time"
        )

        val extraCols = selectColumns.map { col ->
            val dbCol = table.resolveColumn(col)
            if (dbCol != col) "$dbCol AS $col" else dbCol
        }

        val weightProjection = weightColumn?.let { col ->
            val dbCol = table.resolveColumn(col)
            listOf("$dbCol AS weight")
        } ?: emptyList()

        val allCols = (baseCols + extraCols + weightProjection).distinct()

        val selection = StringBuilder()
        val args = mutableListOf<Any>()

        timeFrom?.let { appendClause(selection, "time_ms >= ?").also { _ -> args += it } }
        timeTo?.let { appendClause(selection, "time_ms <= ?").also { _ -> args += it } }

        // Bounds compared in E7 space for index usage
        if (north != null) {
            appendClause(selection, "lat_e7 <= ?")
            args += degreesToE7(north!!)
        }
        if (south != null) {
            appendClause(selection, "lat_e7 >= ?")
            args += degreesToE7(south!!)
        }
        if (east != null) {
            appendClause(selection, "lon_e7 <= ?")
            args += degreesToE7(east!!)
        }
        if (west != null) {
            appendClause(selection, "lon_e7 >= ?")
            args += degreesToE7(west!!)
        }

        // Speed is the one weight column noisy enough to need its own trustworthiness gate: a
        // single low-accuracy or coarse fix can report an implausible instantaneous speed even
        // though the numeric value itself is unremarkable, which would otherwise let one bad fix
        // paint a hot spot on the heatmap or skew the tap-to-probe average/max. Mirrors
        // LocationSample.hasTrustworthySpeed() (feature:statistics TripDetailPresenterViewModel).
        if (table == Table.LOCATION && weightColumn == "speed") {
            appendClause(selection, "speed_mps IS NOT NULL")
            appendClause(selection, "quality NOT IN ('LOW', 'COARSE')")
            appendClause(selection, "(speed_accuracy_mps IS NULL OR speed_accuracy_mps <= $MAX_TRUSTED_SPEED_ACCURACY_MPS)")
        }
        if (table == Table.LOCATION && weightColumn == "alt") {
            appendClause(selection, "alt_m IS NOT NULL")
        }

        val projectionNames = buildList {
            add("lat")
            add("lon")
            add("time")
            addAll(selectColumns)
            if (weightColumn != null) add("weight")
        }.distinct()
        val sql = StringBuilder()
        val baseSelection = selection.toString()
        val filterArgs = args.toList()
        val sampleMax = sampleLimit
        val newestMax = newestLimit
        if (newestMax != null) {
            sql.append("SELECT ")
            sql.append(projectionNames.joinToString())
            sql.append(" FROM (SELECT ")
            sql.append(allCols.joinToString())
            sql.append(" FROM ")
            sql.append(table.tableName)
            if (selection.isNotEmpty()) sql.append(" WHERE ").append(selection)
            sql.append(" ORDER BY time_ms DESC LIMIT ").append(newestMax)
            sql.append(") ORDER BY time ASC")
        } else if (sampleMax != null) {
            sql.append("SELECT ")
            sql.append(allCols.joinToString())
            sql.append(" FROM ")
            sql.append(table.tableName)
            sql.append(" CROSS JOIN (SELECT MIN(id) AS min_id, MAX(id) AS max_id FROM ")
            sql.append(table.tableName)
            if (baseSelection.isNotEmpty()) sql.append(" WHERE ").append(baseSelection)
            sql.append(") AS sample_bounds")
            if (baseSelection.isNotEmpty()) sql.append(" WHERE ").append(baseSelection).append(" AND ")
            else sql.append(" WHERE ")
            sql.append("(").append(table.tableName).append(".id - sample_bounds.min_id) % ")
            sql.append("MAX(1, ((sample_bounds.max_id - sample_bounds.min_id) / ?) + 1) = 0")
            sql.append(" ORDER BY time_ms ASC LIMIT ").append(sampleMax)

            args.clear()
            args.addAll(filterArgs)
            args.addAll(filterArgs)
            args += sampleMax
        } else {
            sql.append("SELECT ")
            sql.append(allCols.joinToString())
            sql.append(" FROM ")
            sql.append(table.tableName)
            if (selection.isNotEmpty()) sql.append(" WHERE ").append(selection)
            sql.append(" ORDER BY time_ms ASC")
            limit?.let { sql.append(" LIMIT ").append(it) }
        }

        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    private fun appendClause(builder: StringBuilder, clause: String) {
        if (builder.isNotEmpty()) builder.append(" AND ")
        builder.append(clause)
    }

    enum class Table(
        val tableName: String,
        val allowedColumns: Set<String>,
        val allowedWeightColumns: Set<String>,
        private val columnMapping: Map<String, String>,
    ) {
        LOCATION(
            "location_sample",
            setOf("speed", "hor_acc", "ver_acc", "alt", "s_acc"),
            setOf("speed", "hor_acc", "ver_acc", "alt", "s_acc", "motion"),
            mapOf(
                "speed" to "speed_mps",
                "hor_acc" to "h_acc_m",
                "ver_acc" to "v_acc_m",
                "alt" to "alt_m",
                "s_acc" to "speed_accuracy_mps",
                "motion" to "CASE motion_state WHEN 'MOVING' THEN 1.0 WHEN 'STILL' THEN 0.5 ELSE 0.0 END",
            ),
        ),
        WIFI(
            "wifi_observation",
            setOf("level", "frequency"),
            setOf("level", "frequency"),
            emptyMap(),
        ),
        CELL(
            "cell_sample",
            setOf("mcc", "mnc", "cell_id", "network_type", "asu"),
            setOf("asu", "network_type"),
            mapOf(
                "asu" to "signal_strength",
                "network_type" to "network_type",
            ),
        );

        /** Map public column name to actual DB column name. Identity if no mapping exists. */
        fun resolveColumn(publicName: String): String = columnMapping[publicName] ?: publicName
    }

    companion object {
        private const val E7_DIVISOR = 10_000_000.0

        // A single low-accuracy or coarse fix can report an implausible instantaneous speed;
        // above this threshold the reading is excluded from speed-weighted queries. Mirrors
        // MAX_TRUSTED_SPEED_ACCURACY_MPS in feature:statistics's TripDetailPresenterViewModel.
        private const val MAX_TRUSTED_SPEED_ACCURACY_MPS = 3.0

        fun location(): SafeQueryBuilder = SafeQueryBuilder(Table.LOCATION)
        fun wifi(): SafeQueryBuilder = SafeQueryBuilder(Table.WIFI)
        fun cell(): SafeQueryBuilder = SafeQueryBuilder(Table.CELL)

        /** Convert degrees to E7 integer for WHERE clause binding. */
        internal fun degreesToE7(degrees: Double): Int = (degrees * E7_DIVISOR).toInt()
    }
}
