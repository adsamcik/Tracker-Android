package com.adsamcik.tracker.map.presentation.bridge

import android.content.Context
import com.adsamcik.tracker.map.layers.registry.LayerRegistry
import com.adsamcik.tracker.map.ui.LayerController
import com.google.android.gms.maps.GoogleMap

/** Temporary bridge for Phase 1 to reuse existing LayerController imperatively. */
class LayerManager(
    private val context: Context,
    private val map: GoogleMap,
    private val registry: LayerRegistry,
) {
    private val controller = LayerController()

    fun selectSingleLayer(id: String?, quality: Float, dateRange: LongRange) {
        val descriptor = id?.let { registry.findById(it) }
        controller.setLayer(context, map, descriptor, quality, dateRange)
    }

    fun clear() {
        controller.clear(map)
    }
}
