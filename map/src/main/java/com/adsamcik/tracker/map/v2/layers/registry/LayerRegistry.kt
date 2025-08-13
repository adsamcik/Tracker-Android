package com.adsamcik.tracker.map.v2.layers.registry

import com.adsamcik.tracker.shared.map.v2.layers.LayerDescriptor

/** Registry abstraction for discovering available layers (v2). */
interface LayerRegistry {
    fun getAllLayers(): List<LayerDescriptor>
    fun findById(id: String): LayerDescriptor? = getAllLayers().firstOrNull { it.id == id }
}
