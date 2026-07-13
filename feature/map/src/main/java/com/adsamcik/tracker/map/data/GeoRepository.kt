package com.adsamcik.tracker.map.data

import com.adsamcik.tracker.shared.base.database.dao.UnifiedGeoDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface GeoRepository {
    fun query(query: GeoQuery): Flow<List<GeoFeature>>
    fun queryWeighted(query: GeoQuery, weightColumn: String): Flow<List<WeightedGeoFeature>>
    fun queryCellSignals(query: GeoQuery): Flow<List<CellSignalGeoFeature>>
    fun queryWeightedAggregated(
        query: GeoQuery,
        weightColumn: String,
        aggregation: Aggregation = Aggregation.Sum,
        cellSizeLatDeg: Double,
        cellSizeLonDeg: Double
    ): Flow<List<WeightedGeoFeature>>
}

class GeoRepositoryImpl(
    private val dao: UnifiedGeoDao,
) : GeoRepository {

    override fun query(query: GeoQuery): Flow<List<GeoFeature>> {
        val builder = when (query.source) {
            GeoSource.LOCATION -> SafeQueryBuilder.location()
            GeoSource.WIFI -> SafeQueryBuilder.wifi()
            GeoSource.CELL -> SafeQueryBuilder.cell()
        }
        query.bounds?.let { builder.bounds(it.north, it.east, it.south, it.west) }
        builder.timeRange(query.timeFrom, query.timeTo)
        query.limit?.let { builder.limit(it) }
        query.sampleLimit?.let { builder.sample(it) }
        query.newestLimit?.let { builder.newest(it) }
        if (query.extraColumns.isNotEmpty()) builder.columns(*query.extraColumns.toTypedArray())
        val weight = query.weight
        if (weight != null) builder.weight(weight)
        val sql = builder.build()

        return when {
            weight != null -> when (query.source) {
                GeoSource.LOCATION -> dao.queryLocationsWeighted(sql).map { list -> list.map { it.toDomain() } }
                GeoSource.WIFI -> dao.queryWifiWeighted(sql).map { list -> list.map { it.toDomain() } }
                GeoSource.CELL -> dao.queryCellsWeighted(sql).map { list -> list.map { it.toDomain() } }
            }
            else -> when (query.source) {
                GeoSource.LOCATION -> dao.queryLocations(sql).map { list -> list.map { it.toDomain() } }
                GeoSource.WIFI -> dao.queryWifi(sql).map { list -> list.map { it.toDomain() } }
                GeoSource.CELL -> dao.queryCells(sql).map { list -> list.map { it.toDomain() } }
            }
        }
    }

    override fun queryWeighted(query: GeoQuery, weightColumn: String): Flow<List<WeightedGeoFeature>> {
        // Force weight column regardless of initial query.weight
        val weightedQuery = query.copy(weight = weightColumn)
        val builder = when (weightedQuery.source) {
            GeoSource.LOCATION -> SafeQueryBuilder.location()
            GeoSource.WIFI -> SafeQueryBuilder.wifi()
            GeoSource.CELL -> SafeQueryBuilder.cell()
        }
        weightedQuery.bounds?.let { builder.bounds(it.north, it.east, it.south, it.west) }
        builder.timeRange(weightedQuery.timeFrom, weightedQuery.timeTo)
        weightedQuery.limit?.let { builder.limit(it) }
        weightedQuery.sampleLimit?.let { builder.sample(it) }
        weightedQuery.newestLimit?.let { builder.newest(it) }
        if (weightedQuery.extraColumns.isNotEmpty()) builder.columns(*weightedQuery.extraColumns.toTypedArray())
        builder.weight(weightColumn)
        val sql = builder.build()
        return when (weightedQuery.source) {
            GeoSource.LOCATION -> dao.queryLocationsWeighted(sql).map { it.map { w -> w.toDomain() } }
            GeoSource.WIFI -> dao.queryWifiWeighted(sql).map { it.map { w -> w.toDomain() } }
            GeoSource.CELL -> dao.queryCellsWeighted(sql).map { it.map { w -> w.toDomain() } }
        }
    }

    override fun queryCellSignals(query: GeoQuery): Flow<List<CellSignalGeoFeature>> {
        require(query.source == GeoSource.CELL) { "Cell signal queries require CELL source" }
        val builder = SafeQueryBuilder.cell()
        query.bounds?.let { builder.bounds(it.north, it.east, it.south, it.west) }
        builder.timeRange(query.timeFrom, query.timeTo)
        query.limit?.let { builder.limit(it) }
        query.sampleLimit?.let { builder.sample(it) }
        query.newestLimit?.let { builder.newest(it) }
        builder.columns("network_type")
        builder.weight("asu")
        val sql = builder.build()

        return dao.queryCellSignals(sql).map { list -> list.map { it.toDomain() } }
    }

    override fun queryWeightedAggregated(
        query: GeoQuery,
        weightColumn: String,
        aggregation: Aggregation,
        cellSizeLatDeg: Double,
        cellSizeLonDeg: Double
    ): Flow<List<WeightedGeoFeature>> {
        require(cellSizeLatDeg > 0 && cellSizeLonDeg > 0) { "Cell size must be > 0" }
        val b = query.bounds ?: error("Bounds required for aggregation")
        val baseFlow = queryWeighted(query, weightColumn)
        return baseFlow.map { points ->
            if (points.isEmpty()) return@map emptyList()
            val south = b.south
            val west = b.west
            val grid = HashMap<Long, MutableList<WeightedGeoFeature>>()
            val invLat = 1.0 / cellSizeLatDeg
            val invLon = 1.0 / cellSizeLonDeg
            points.forEach { p ->
                val latIdx = ((p.lat - south) * invLat).toLong()
                val lonIdx = ((p.lon - west) * invLon).toLong()
                val key = (latIdx shl 32) xor lonIdx
                val list = grid.getOrPut(key) { mutableListOf() }
                list += p
            }
            grid.values.map { cellPoints ->
                val representative = aggregation.reduce(cellPoints)
                representative
            }
        }
    }
}

sealed interface Aggregation {
    fun reduce(points: List<WeightedGeoFeature>): WeightedGeoFeature

    data object Sum : Aggregation {
        override fun reduce(points: List<WeightedGeoFeature>): WeightedGeoFeature {
            var sum = 0.0
            var lat = 0.0
            var lon = 0.0
            var time = Long.MIN_VALUE
            points.forEach { p ->
                sum += p.weight
                lat += p.lat
                lon += p.lon
                if (p.time > time) time = p.time
            }
            val n = points.size.toDouble()
            return WeightedGeoFeature(lat / n, lon / n, time, sum)
        }
    }

    data object Avg : Aggregation {
        override fun reduce(points: List<WeightedGeoFeature>): WeightedGeoFeature {
            var sum = 0.0
            var lat = 0.0
            var lon = 0.0
            var time = Long.MIN_VALUE
            points.forEach { p ->
                sum += p.weight
                lat += p.lat
                lon += p.lon
                if (p.time > time) time = p.time
            }
            val n = points.size.toDouble()
            return WeightedGeoFeature(lat / n, lon / n, time, sum / n)
        }
    }

    data object Max : Aggregation {
        override fun reduce(points: List<WeightedGeoFeature>): WeightedGeoFeature {
            var max = Double.NEGATIVE_INFINITY
            var lat = 0.0
            var lon = 0.0
            var time = Long.MIN_VALUE
            points.forEach { p ->
                if (p.weight > max) {
                    max = p.weight
                    lat = p.lat
                    lon = p.lon
                }
                if (p.time > time) time = p.time
            }
            return WeightedGeoFeature(lat, lon, time, max)
        }
    }

    /**
     * Count of points per grid cell. Weight is the number of items; lat/lon averaged; time = latest.
     */
    data object Count : Aggregation {
        override fun reduce(points: List<WeightedGeoFeature>): WeightedGeoFeature {
            var lat = 0.0
            var lon = 0.0
            var time = Long.MIN_VALUE
            points.forEach { p ->
                lat += p.lat
                lon += p.lon
                if (p.time > time) time = p.time
            }
            val n = points.size.toDouble()
            return WeightedGeoFeature(lat / n, lon / n, time, n)
        }
    }
}
