package com.adsamcik.tracker.map.tiles

import android.util.Log
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1: In-memory async cache for aggregated heatmap point queries.
 * Provides ensure() to start a fetch and get() to retrieve current data snapshot.
 * When data becomes available, notifies listeners so tile overlay can invalidate.
 */
internal class HeatmapDataCache(
    private val repo: GeoRepository,
    private val scope: CoroutineScope,
    private val onEntryReady: () -> Unit, // debounced invalidation trigger provided by layer
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface EntryState { object Loading: EntryState; data class Ready(val data: List<WeightedGeoFeature>): EntryState; object Empty: EntryState }

    private data class Key(
        val source: Int,
        val weightColumn: String,
        val aggregation: String,
        val zoom: Int,
        val x: Int,
        val y: Int,
        val cellLatE6: Int,
        val cellLonE6: Int,
    )

    private val entries = ConcurrentHashMap<Key, EntryState>()
    private val inFlight = ConcurrentHashMap<Key, Job>()

    fun get(
        source: Int,
        weightColumn: String,
        aggregation: String,
        zoom: Int,
        x: Int,
        y: Int,
        cellLatDeg: Double,
        cellLonDeg: Double,
    ): EntryState? = entries[Key(source, weightColumn, aggregation, zoom, x, y, (cellLatDeg * 1_000_000).toInt(), (cellLonDeg * 1_000_000).toInt())]

    fun ensure(
        query: GeoQuery,
        weightColumn: String,
        aggregation: String,
        zoom: Int,
        x: Int,
        y: Int,
        cellLatDeg: Double,
        cellLonDeg: Double,
        aggregationEnum: com.adsamcik.tracker.map.data.Aggregation,
    ) {
        val key = Key(query.source.ordinal, weightColumn, aggregation, zoom, x, y, (cellLatDeg * 1_000_000).toInt(), (cellLonDeg * 1_000_000).toInt())
        if (entries[key] is EntryState.Ready || entries[key] is EntryState.Empty) return
        if (inFlight.containsKey(key)) return
        entries.putIfAbsent(key, EntryState.Loading)
    val job = scope.launch(ioDispatcher) {
            try {
                val data = repo.queryWeightedAggregated(
                    query = query,
                    weightColumn = weightColumn,
                    aggregation = aggregationEnum,
                    cellSizeLatDeg = cellLatDeg,
                    cellSizeLonDeg = cellLonDeg,
                ).first()
                entries[key] = if (data.isEmpty()) EntryState.Empty else EntryState.Ready(data)
            } catch (e: Throwable) {
                Log.w("HeatmapDataCache", "Failed to query aggregated data for tile ($x, $y) at zoom $zoom: ${e.message}")
                entries[key] = EntryState.Empty
            } finally {
                inFlight.remove(key)
                onEntryReady()
            }
        }
        inFlight[key] = job
    }

    fun clear() {
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        entries.clear()
    }
}
