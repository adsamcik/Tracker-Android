package com.adsamcik.tracker.app.settings.map

import kotlin.math.abs
import kotlin.math.roundToInt

internal object MapSettingOptions {
    val quality = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
    val maxHeat = listOf(1, 3, 5, 8, 10, 12, 15, 20, 25, 30, 40, 50, 100)
    val visitThresholdSeconds = listOf(
        0, 5, 10, 15, 20, 30, 45, 60, 120, 300, 600, 1800, 3600, 7200, 21600, 43200, 86400
    )
}

internal fun sliderIndexForValue(value: Float, options: List<Float>): Float {
    require(options.isNotEmpty()) { "Options must not be empty" }
    return options.indices.minByOrNull { abs(options[it] - value) }?.toFloat() ?: 0f
}

internal fun sliderIndexForValue(value: Int, options: List<Int>): Float {
    require(options.isNotEmpty()) { "Options must not be empty" }
    return options.indices.minByOrNull { abs(options[it] - value) }?.toFloat() ?: 0f
}

internal fun valueForSliderIndex(sliderIndex: Float, options: List<Float>): Float {
    require(options.isNotEmpty()) { "Options must not be empty" }
    return options[sliderIndex.roundToInt().coerceIn(0, options.lastIndex)]
}

internal fun valueForSliderIndex(sliderIndex: Float, options: List<Int>): Int {
    require(options.isNotEmpty()) { "Options must not be empty" }
    return options[sliderIndex.roundToInt().coerceIn(0, options.lastIndex)]
}

internal fun formatVisitThreshold(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60} min"
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes == 0) {
                "${hours}h"
            } else {
                "${hours}h ${minutes}min"
            }
        }
    }
}
