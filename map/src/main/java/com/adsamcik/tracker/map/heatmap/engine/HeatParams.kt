package com.adsamcik.tracker.map.heatmap.engine

data class HeatParams(
    val dtMinutes: Int = 15,
    val hsMetersBase: Double = 200.0,
    val htMinutesBase: Int = 30,
    val supersample: Int = 1,
    val extraBlurPx: Int = 0,
    val multiscale: Boolean = false,
    val multiscaleLevels: Int = 3,
    val useFlows: Boolean = false
)
