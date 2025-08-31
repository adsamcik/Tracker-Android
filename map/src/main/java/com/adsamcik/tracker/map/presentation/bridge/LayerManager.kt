package com.adsamcik.tracker.map.presentation.bridge

import android.content.Context
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.ui.LayerController
import com.google.android.gms.maps.GoogleMap
import com.adsamcik.tracker.shared.map.MapLayerData

/** Temporary bridge for Phase 1 to reuse existing LayerController imperatively. */
class LayerManager(
    private val context: Context,
    private val map: GoogleMap,
    private val registry: LayerRegistry,
) : LayerEngine {
    private val controller = LayerController()

    override fun selectSingleLayer(id: String?, quality: Float, dateRange: LongRange) {
        val descriptor = id?.let { registry.findById(it) }
        controller.setLayer(context, map, descriptor, quality, dateRange)
    }

    override fun clear() {
        controller.clear(map)
    }

    override fun activeLegend(): MapLayerData? = controller.activeLegend()

    override fun activeTileProvider(): com.google.android.gms.maps.model.TileProvider? = controller.activeTileProvider()

    override fun overlays(): kotlinx.collections.immutable.ImmutableList<com.adsamcik.tracker.map.presentation.udf.MapOverlayState> {
        val layer = controller.activeLayerUnsafe()
        // Currently only LocationPathLayer renders polylines imperatively; map to declarative polyline if possible.
        return if (layer is com.adsamcik.tracker.map.layers.impl.LocationPathLayer) {
            val options: List<com.google.android.gms.maps.model.PolylineOptions> = layer.currentOptionsSnapshot()
            val mapped = options.map { opt ->
                val pts = opt.points.map { p ->
                    com.adsamcik.tracker.map.presentation.udf.LatLngModel(p.latitude, p.longitude)
                }
                com.adsamcik.tracker.map.presentation.udf.MapOverlayState.Polyline(
                    points = kotlinx.collections.immutable.persistentListOf(*pts.toTypedArray()),
                    colorArgb = opt.color,
                    widthPx = opt.width
                )
            }
            kotlinx.collections.immutable.persistentListOf(*mapped.toTypedArray())
        } else {
            kotlinx.collections.immutable.persistentListOf()
        }
    }
}
