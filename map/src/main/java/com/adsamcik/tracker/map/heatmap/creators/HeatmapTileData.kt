package com.adsamcik.tracker.map.heatmap.creators

import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.shared.map.CoordinateBounds

/** Data class for heatmap tile generation. */
internal data class HeatmapTileData(
    val config: HeatmapConfig,
    val stamp: HeatmapStamp,
    val heatmapSize: Int,
    val x: Int,
    val y: Int,
    val zoom: Int,
    val area: CoordinateBounds
)
