package com.adsamcik.tracker.map.layers.registry

import com.adsamcik.tracker.map.shared.layers.LayerDescriptor

/** Registry abstraction for discovering available layers. */
interface LayerRegistry {
    fun getAllLayers(): List<LayerDescriptor>
    fun findById(id: String): LayerDescriptor? = getAllLayers().firstOrNull { it.id == id }
}
