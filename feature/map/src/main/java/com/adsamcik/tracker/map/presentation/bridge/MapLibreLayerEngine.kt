package com.adsamcik.tracker.map.presentation.bridge

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoRepositoryImpl
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.boundsAround
import com.adsamcik.tracker.map.data.haversineMeters
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.ui.LayerController
import com.adsamcik.tracker.map.shared.MapLayerData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.first

/**
 * MapLibre-based [LayerEngine] implementation.
 * Does NOT need a map instance -- delegates to [LayerController] which
 * produces [MapLibreLayerConfig] data for the Compose layer to render.
 */
class MapLibreLayerEngine(
    private val context: Context,
    private val registry: LayerRegistry,
) : LayerEngine {

    private val controller = LayerController()

    /**
     * Lazily-built read-only geo repository for ad-hoc point queries (e.g. the interactive speed
     * heatmap). The layer factories build their own repositories per layer; this one is dedicated
     * to engine-level queries that aren't tied to a rendered layer.
     */
    private val geoRepository: GeoRepository by lazy {
        GeoRepositoryImpl(AppDatabase.database(context).unifiedGeoDao())
    }

    override suspend fun selectLayers(ids: Set<String>, quality: Float, dateRange: LongRange, bounds: Bounds?, zoom: Float) {
        val descriptors = ids.mapNotNull { registry.findById(it) }
        controller.setLayers(context, descriptors, quality, dateRange, bounds, zoom)
    }

    override suspend fun refreshLayersInPlace(
        bounds: Bounds?,
        zoom: Float,
        dateRange: LongRange,
        forceReload: Boolean,
    ): LayerRefreshResult =
        controller.refreshLayersInPlace(context, bounds, zoom, dateRange, forceReload)

    override fun clear() {
        controller.clear()
    }

    override fun activeLegend(): MapLayerData? = controller.activeLegend()

    override fun activeLayerConfig(): MapLibreLayerConfig? = controller.activeLayerConfig()

    override fun overlays(): ImmutableList<MapOverlayState> {
        // Overlays (user marker, accuracy circle) are managed by MapStore directly.
        // Layer-produced overlays could be added here in the future.
        return persistentListOf()
    }

    override suspend fun querySpeedSummaryAt(
        lat: Double,
        lng: Double,
        radiusMeters: Double,
        dateRange: LongRange,
    ): SpeedSummary? {
        val bounds = boundsAround(lat, lng, radiusMeters) ?: return null
        val query = GeoQuery(
            source = GeoSource.LOCATION,
            bounds = bounds,
            timeFrom = dateRange.first.takeIf { it > 0L },
            timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
            weight = "speed",
        )
        // Weight is the raw `speed` column in m/s. The bounds box is a superset of the tap circle,
        // so distance-filter to keep only samples actually within radiusMeters. Drop sentinel
        // negative speeds (unknown), but keep 0 m/s (legitimately stationary).
        val speeds = geoRepository.queryWeighted(query, "speed").first()
            .asSequence()
            .filter { it.weight >= 0.0 && haversineMeters(lat, lng, it.lat, it.lon) <= radiusMeters }
            .map { it.weight }
            .toList()
        if (speeds.isEmpty()) return null
        return SpeedSummary(
            avgSpeedMps = speeds.average(),
            maxSpeedMps = speeds.max(),
            sampleCount = speeds.size,
        )
    }

    override fun destroy() {
        clear()
        controller.destroy()
    }
}
