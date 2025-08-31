package com.adsamcik.tracker.map.heatmap

/** Minimal read-only view to decouple policies from engine internals. */
interface ReadOnlyHeatmap {
    fun weightAt(x: Int, y: Int): Float
}
