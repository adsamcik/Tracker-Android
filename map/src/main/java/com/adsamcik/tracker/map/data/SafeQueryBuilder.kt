package com.adsamcik.tracker.map.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * SafeQueryBuilder builds parameterized spatial/temporal raw queries against underlying
 * geo tables (location_data, wifi_data, cell_location) restricting selectable columns
 * and predicates to a vetted allow-list to reduce SQL injection risk.
 *
 * Usage:
 *  val query = SafeQueryBuilder.location()
 *      .bounds(north, east, south, west)
 *      .timeRange(start, end)
 *      .columns("lat", "lon", "time", "speed") // allowed extras only
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
    private var weightColumn: String? = null

    /**
     * Limit result rows (defensive cap for heavy queries)
     */
    fun limit(max: Int): SafeQueryBuilder = apply {
        require(max > 0) { "limit must be > 0" }
        limit = max
    }

    /**
     * Add columns to projection (always includes lat, lon, time implicitly).
     * Only columns from the allow list for the selected table are permitted.
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
     */
    fun weight(column: String): SafeQueryBuilder = apply {
        require(table.allowedWeightColumns.contains(column)) { "Column '$column' not allowed as weight for ${table.tableName}" }
        weightColumn = column
    }

    /**
     * Set inclusive time range.
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
        // Base mandatory columns for GeoFeatureEntity mapping
        val baseCols = when (table) {
            Table.WIFI -> listOf(
                // wifi_data columns are latitude/longitude/last_seen -> alias to lat/lon/time
                "latitude AS lat", "longitude AS lon", "last_seen AS time"
            )
            else -> listOf("lat", "lon", timeColumn())
        }
    val weightProjection = weightColumn?.let { listOf("$it AS weight") } ?: emptyList()
    val allCols = (baseCols + selectColumns + weightProjection).distinct()

        val sql = StringBuilder()
        sql.append("SELECT ")
    sql.append(allCols.joinToString())
        sql.append(" FROM ")
        sql.append(table.tableName)

        val selection = StringBuilder()
        val args = mutableListOf<Any>()

    val timeCol = when (table) { Table.WIFI -> "last_seen" else -> timeColumn() }
    timeFrom?.let { appendClause(selection, "$timeCol >= ?").also { args += it } }
    timeTo?.let { appendClause(selection, "$timeCol <= ?").also { args += it } }
        if (north != null) {
            appendClause(selection, "${latColumn()} <= ?")
            args += north as Double
        }
        if (south != null) {
            appendClause(selection, "${latColumn()} >= ?")
            args += south as Double
        }
        if (east != null) {
            appendClause(selection, "${lonColumn()} <= ?")
            args += east as Double
        }
        if (west != null) {
            appendClause(selection, "${lonColumn()} >= ?")
            args += west as Double
        }

        if (selection.isNotEmpty()) {
            sql.append(" WHERE ").append(selection)
        }

        // Basic ordering for deterministic slices
    sql.append(" ORDER BY $timeCol ASC")
        limit?.let { sql.append(" LIMIT ").append(it) }

        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    private fun timeColumn(): String = when (table) {
        Table.LOCATION -> "time"
        Table.WIFI -> "last_seen"
        Table.CELL -> "time"
    }

    private fun latColumn(): String = when (table) {
        Table.WIFI -> "latitude"
        else -> "lat"
    }

    private fun lonColumn(): String = when (table) {
        Table.WIFI -> "longitude"
        else -> "lon"
    }

    private fun appendClause(builder: StringBuilder, clause: String) {
        if (builder.isNotEmpty()) builder.append(" AND ")
        builder.append(clause)
    }

    enum class Table(
        val tableName: String,
        val allowedColumns: Set<String>,
        val allowedWeightColumns: Set<String>
    ) {
        LOCATION(
            "location_data",
            setOf(
                // numeric columns we may project besides base lat/lon/time
                "speed", "hor_acc", "ver_acc", "alt", "s_acc"
            ),
            setOf("speed", "hor_acc", "ver_acc", "s_acc")
        ),
        WIFI(
            "wifi_data",
            setOf(
                "altitude", "level", "frequency", "first_seen", "last_seen" // lat/lon may be null originally
            ),
            setOf("level", "frequency")
        ),
        CELL(
            "cell_location",
            setOf(
                "mcc", "mnc", "cell_id", "type", "asu", "alt" // lat/lon/time already base columns
            ),
            setOf("asu", "type")
        );
    }

    companion object {
        fun location(): SafeQueryBuilder = SafeQueryBuilder(Table.LOCATION)
        fun wifi(): SafeQueryBuilder = SafeQueryBuilder(Table.WIFI)
        fun cell(): SafeQueryBuilder = SafeQueryBuilder(Table.CELL)
    }
}
