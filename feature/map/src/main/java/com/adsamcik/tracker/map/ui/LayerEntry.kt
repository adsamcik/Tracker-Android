package com.adsamcik.tracker.map.ui

import android.content.Context
import com.adsamcik.tracker.map.layers.base.BaseMapLayer
import com.adsamcik.tracker.map.shared.MapLayerData

/**
 * Factory-based wrapper for layers that need Context to construct.
 * Holds a builder to create the layer on demand and the legend data for UI.
 */
data class LayerEntry(
    val build: (Context) -> BaseMapLayer<*, *>,
    val legend: MapLayerData
)
