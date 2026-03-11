package com.adsamcik.tracker.map.shared.layers

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Describes a map layer for discovery/selection. */
data class LayerDescriptor(
    val id: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int?,
    val capabilities: LayerCapabilities,
    val recipe: LayerRecipe
)

/** High-level booleans indicating what a layer supports. */
data class LayerCapabilities(
    val supportsDateRange: Boolean = true,
    val supportsQuality: Boolean = true,
    val supportsFollowInteraction: Boolean = false,
    val isHeatmap: Boolean = false,
    val isPolyline: Boolean = false
)

/** Layer factory recipe & default parameter set. */
data class LayerRecipe(
    val factory: LayerFactory,
    val defaultParams: Map<String, LayerParameter<*>> = emptyMap()
)

/** Generic parameter definition placeholder. */
data class LayerParameter<T>(
    val key: String,
    val defaultValue: T
)

fun interface LayerFactory {
    fun create(): Any /* Returns layer instances */
}
