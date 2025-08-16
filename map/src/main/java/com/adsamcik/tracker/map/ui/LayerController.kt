package com.adsamcik.tracker.map.ui

import android.content.Context
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.layers.LayerDescriptor
import com.google.android.gms.maps.GoogleMap

/** Controller that enables/disables a single active map layer. */
class LayerController {
    private var activeLayer: BaseMapLayer<*, *>? = null
    private var activeLegend: MapLayerData? = null

    fun setLayer(
        context: Context,
        map: GoogleMap,
        descriptor: LayerDescriptor?,
        quality: Float,
        dateRange: LongRange
    ) {
        if (descriptor == null) {
            clear(map)
            return
        }

        // Disable previous layer
        activeLayer?.disable()
        activeLayer = null
        activeLegend = null

        val factoryProduct = descriptor.recipe.factory.create()
        when (factoryProduct) {
            is LayerEntry -> {
                val layer = factoryProduct.build(context)
                if (layer is SupportsDateRange) {
                    layer.dateRange = dateRange
                }
                layer.enable(context, map, quality)
                activeLayer = layer
                activeLegend = factoryProduct.legend
            }
            else -> {
                // Unknown; ignore safely
            }
        }
    }

    fun clear(map: GoogleMap) {
        activeLayer?.disable()
        activeLayer = null
        activeLegend = null
    }

    fun activeLegend(): MapLayerData? = activeLegend
}
