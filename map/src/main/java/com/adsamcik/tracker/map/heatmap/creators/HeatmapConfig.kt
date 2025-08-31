package com.adsamcik.tracker.map.heatmap.creators

import com.adsamcik.tracker.map.heatmap.HeatmapColorScheme
import com.adsamcik.tracker.map.heatmap.ReadOnlyHeatmap
import com.adsamcik.tracker.map.heatmap.implementation.AlphaMergeFunction
import com.adsamcik.tracker.map.heatmap.implementation.WeightMergeFunction

/** Configuration for heatmap generation. */
internal data class HeatmapConfig(
    val colorScheme: HeatmapColorScheme,
    val maxHeat: Float,
    val ageThreshold: Int,
    val weightMergeFunction: WeightMergeFunction,
    val alphaMergeFunction: AlphaMergeFunction,
    // Optional transform to remap normalized value [0,1] before color lookup (e.g., midrange emphasis)
    val valueCurve: ((Float) -> Float)? = null,
    // If true, render alpha from normalized intensity instead of stored alpha buffer
    val alphaFromNormalized: Boolean = true,
    // Overall opacity multiplier when alphaFromNormalized is used (0..1)
    val opacity: Float = 0.9f,
    // Preferred read-only weight policy using decoupled view
    val weightPolicyRo: ((baseWeight: Float, heatmap: ReadOnlyHeatmap, cx: Int, cy: Int, ageInSeconds: Int) -> Float)? = null
)

