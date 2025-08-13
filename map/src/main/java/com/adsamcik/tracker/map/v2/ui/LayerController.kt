package com.adsamcik.tracker.map.v2.ui

import android.content.Context
import com.adsamcik.tracker.shared.map.MapLayerLogic
import com.adsamcik.tracker.shared.map.v2.layers.LayerDescriptor
import com.google.android.gms.maps.GoogleMap

/** Minimal layer controller that enables / disables a single active layer from a descriptor factory. */
class LayerController {
    private var active: MapLayerLogic? = null

    fun setLayer(context: Context, map: GoogleMap, descriptor: LayerDescriptor?, quality: Float, dateRange: LongRange) {
        if (descriptor == null) {
            clear(map)
            return
        }
        val existing = active
    if (existing != null && existing.layerInfo.nameRes == descriptor.titleRes) {
            return // already active
        }
        existing?.onDisable(map)
        @Suppress("UNCHECKED_CAST")
        val logic = descriptor.recipe.factory.create() as MapLayerLogic
        logic.quality = quality
        logic.dateRange = dateRange
        logic.onEnable(context, map, quality)
        active = logic
    }

    fun clear(map: GoogleMap) {
        active?.onDisable(map)
        active = null
    }
}
