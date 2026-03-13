package com.adsamcik.tracker.map.presentation.bridge

import android.content.Context
import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.presentation.udf.MapOverlayState
import com.adsamcik.tracker.map.ui.LayerController
import com.adsamcik.tracker.map.shared.MapLayerData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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

    override suspend fun selectLayers(ids: Set<String>, quality: Float, dateRange: LongRange, bounds: Bounds?, zoom: Float) {
        val descriptors = ids.mapNotNull { registry.findById(it) }
        controller.setLayers(context, descriptors, quality, dateRange, bounds, zoom)
    }

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

    override fun destroy() {
        clear()
        controller.destroy()
    }
}
