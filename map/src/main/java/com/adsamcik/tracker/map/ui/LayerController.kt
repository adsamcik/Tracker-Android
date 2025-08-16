package com.adsamcik.tracker.map.ui

import android.content.Context
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.layers.base.SupportsDateRange
import com.adsamcik.tracker.shared.map.MapLayerData
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.map.v2.layers.LayerDescriptor
import com.google.android.gms.maps.GoogleMap

/** Controller that enables/disables a single active map layer. */
class LayerController {
    private var activeV1: MapLayerLogic? = null
    private var activeV2: BaseMapLayer<*, *>? = null
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

        // Disable previous regardless of type
        activeV1?.onDisable(map)
        activeV2?.disable()
        activeV1 = null
        activeV2 = null
        activeLegend = null

        val factoryProduct = descriptor.recipe.factory.create()
        when (factoryProduct) {
            is MapLayerLogic -> {
                factoryProduct.quality = quality
                factoryProduct.dateRange = dateRange
                factoryProduct.onEnable(context, map, quality)
                activeV1 = factoryProduct
                activeLegend = factoryProduct.layerData()
            }
            is LayerEntry -> {
                val layer = factoryProduct.build(context)
                if (layer is SupportsDateRange) {
                    layer.dateRange = dateRange
                }
                layer.enable(context, map, quality)
                activeV2 = layer
                activeLegend = factoryProduct.legend
            }
            else -> {
                // Unknown; ignore safely
            }
        }
    }

    fun clear(map: GoogleMap) {
        activeV1?.onDisable(map)
        activeV2?.disable()
        activeV1 = null
        activeV2 = null
        activeLegend = null
    }

    fun activeLayerV1(): MapLayerLogic? = activeV1
    fun activeLegend(): MapLayerData? = activeLegend
}
