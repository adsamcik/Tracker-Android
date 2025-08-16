package com.adsamcik.tracker.map.heatmap.creators

import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.implementation.AlphaMergeFunction
import com.adsamcik.tracker.map.heatmap.implementation.WeightMergeFunction

/** Configuration for heatmap generation. */
internal data class HeatmapConfig(
    val colorScheme: HeatmapColorScheme,
    val maxHeat: Float,
    val dynamicHeat: Boolean = false,
    val ageThreshold: Int,
    val weightMergeFunction: WeightMergeFunction,
    val alphaMergeFunction: AlphaMergeFunction
)
