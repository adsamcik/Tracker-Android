package com.adsamcik.tracker.map.heatmap.engine

/** A 15-min resampled event used by the engine. */
data class Event(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val weight: Double,
    val speedMps: Double = 0.0
)
