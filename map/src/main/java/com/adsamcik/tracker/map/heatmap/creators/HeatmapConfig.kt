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
    val alphaMergeFunction: AlphaMergeFunction,
    // Optional transform to remap normalized value [0,1] before color lookup (e.g., midrange emphasis)
    val valueCurve: ((Float) -> Float)? = null,
    // If true, render alpha from normalized intensity instead of stored alpha buffer
    val alphaFromNormalized: Boolean = true,
    // Overall opacity multiplier when alphaFromNormalized is used (0..1)
    val opacity: Float = 0.9f,
    // Optional revisit interval in seconds. If > 0, repeated contributions to the same pixel within
    // this interval are smoothly gated, requiring roughly this much time between visits to fully
    // contribute again. Movement still expands the stamp smoothly to neighboring pixels.
    val revisitIntervalSec: Int = 0,
    // Easing function for revisit gating
    val revisitEasing: RevisitEasing = RevisitEasing.Smoothstep,
    // Strength parameter for easing curves (used by Exponential/Power modes)
    val revisitEasingStrength: Float = 3f
)

/** Easing function for revisit gating curve. */
internal enum class RevisitEasing {
    Smoothstep,
    Exponential,
    Power
}
