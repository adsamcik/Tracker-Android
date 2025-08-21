package com.adsamcik.tracker.map.heatmap.creators

import com.adsamcik.tracker.map.heatmap.HeatmapStamp
import com.adsamcik.tracker.map.heatmap.RenderPolicy
import com.adsamcik.tracker.shared.base.database.data.location.TimeLocation2DWeighted
import com.adsamcik.tracker.shared.map.CoordinateBounds

/** Data class for heatmap tile generation. */
internal data class HeatmapTileData(
    val config: HeatmapConfig,
    val stamp: HeatmapStamp,
    // Optional dynamic stamp provider; if present, used per-point to control blur/radius
    val stampProvider: ((TimeLocation2DWeighted) -> HeatmapStamp)? = null,
    // Optional secondary wide-area kernel to promote continuity across sparse nodes
    val ambientStampProvider: ((TimeLocation2DWeighted) -> HeatmapStamp)? = null,
    val ambientWeightScale: Float = 0.3f,
    val heatmapSize: Int,
    val x: Int,
    val y: Int,
    val zoom: Int,
    val area: CoordinateBounds,
    // Overscan padding (in heatmap pixels) added on each side; helps avoid edge clipping of stamps
    val pad: Int = 0,
    // Optional override for saturation (normalization max). If null, tile computes its own percentile.
    val saturationOverride: Float? = null,
    // Optional per-layer render policy; defaults to built-in if not provided.
    val renderPolicy: RenderPolicy? = null
)
