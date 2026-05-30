package com.adsamcik.tracker.map.layers.impl

internal object HeatmapColorRamps {
    val LocationDensity: List<Pair<Float, Int>> = listOf(
        0.0f to 0x000000FF,
        0.2f to 0xFF0078FF.toInt(),
        0.45f to 0xFF00C878.toInt(),
        0.7f to 0xFFFFFF00.toInt(),
        0.9f to 0xFFFF8C00.toInt(),
        1.0f to 0xFFFF0000.toInt(),
    )

    val Speed: List<Pair<Float, Int>> = listOf(
        0.0f to 0xFF9966FF.toInt(),
        0.2f to 0xFF66CCFF.toInt(),
        0.4f to 0xFF66FF66.toInt(),
        0.6f to 0xFFFFFF66.toInt(),
        0.8f to 0xFFFF8000.toInt(),
        1.0f to 0xFFFF3333.toInt(),
    )

    val CellSignal: List<Pair<Float, Int>> = listOf(
        0.0f to 0xFF440154.toInt(),
        0.25f to 0xFF3B528B.toInt(),
        0.5f to 0xFF21918C.toInt(),
        0.75f to 0xFF5EC962.toInt(),
        1.0f to 0xFFFDE725.toInt(),
    )

    /**
     * Five-stop ramp for the vehicle speed compliance layer.
     * Indexes (in bucket order) are: way-under, slow, at-limit, slightly-over, speeding.
     * Plain hex literals are used so unit tests can read the values without an
     * Android runtime (where [android.graphics.Color.rgb] returns 0).
     */
    val VehicleCompliance: List<Pair<Float, Int>> = listOf(
        0.0f to 0xFF0D47A1.toInt(),
        0.25f to 0xFF2196F3.toInt(),
        0.5f to 0xFF4CAF50.toInt(),
        0.75f to 0xFFFFC107.toInt(),
        1.0f to 0xFFF44336.toInt(),
    )
}
