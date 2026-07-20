package com.adsamcik.tracker.map.layers.impl

internal object HeatmapColorRamps {
	/**
	 * Absolute observation-supported-time scale. Empty cells are not emitted, so the first
	 * stop remains visible. Unlike viewport-relative density, the same duration keeps the same color
	 * while panning; [ObservedPresenceLayer] applies a fixed log scale capped at eight hours.
	 */
	val ObservedPresenceTime: List<Pair<Float, Int>> = listOf(
		0.0f to 0xFF283593.toInt(),
		0.4f to 0xFF1976D2.toInt(),
		0.6f to 0xFF00A896.toInt(),
		0.8f to 0xFFFFB300.toInt(),
		1.0f to 0xFFD84315.toInt(),
	)

    // Density-based heatmap ramps MUST start with a transparent stop at density 0.0. MapLibre's
    // `heatmap-color` is evaluated for every pixel (density is >= 0 everywhere), so an opaque 0.0
    // stop tints the entire map — including locations with no collected data.
    val LocationDensity: List<Pair<Float, Int>> = listOf(
        0.0f to 0x000000FF,
        0.2f to 0xFF0078FF.toInt(),
        0.45f to 0xFF00C878.toInt(),
        0.7f to 0xFFFFFF00.toInt(),
        0.9f to 0xFFFF8C00.toInt(),
        1.0f to 0xFFFF0000.toInt(),
    )

    val Speed: List<Pair<Float, Int>> = listOf(
        0.0f to 0x009966FF,
        0.2f to 0xFF66CCFF.toInt(),
        0.4f to 0xFF66FF66.toInt(),
        0.6f to 0xFFFFFF66.toInt(),
        0.8f to 0xFFFF8000.toInt(),
        1.0f to 0xFFFF3333.toInt(),
    )

    /**
     * Ramp for the signal dead-zone layer. Its weight is *inverted* — high weight means weak/absent
     * signal — so strong
     * signal fades to transparent (you have coverage there, nothing to flag) while progressively
     * weaker signal glows yellow → orange → red. The 0.0 stop MUST stay transparent: MapLibre's
     * `heatmap-color` is evaluated at every pixel, and strong-signal cells contribute ~0 density, so
     * an opaque low stop would tint the whole map instead of only the poorly-covered areas.
     */
    val SignalDeadZone: List<Pair<Float, Int>> = listOf(
        0.0f to 0x00FFF176,
        0.4f to 0xFFFFF176.toInt(),
        0.6f to 0xFFFFA726.toInt(),
        0.8f to 0xFFFF7043.toInt(),
        1.0f to 0xFFD50000.toInt(),
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

    /**
     * Opaque ramp for the legacy grid-tile heatmap. Unlike the density ramps above, every stop is
     * fully opaque: each tile *is* collected data (empty cells aren't rendered at all), so the
     * lowest bucket must still be visible rather than fading to transparent. Blue (low) → cyan →
     * green → yellow → red (high), echoing the old Signals/Advention server-rendered tiles.
     */
    val LegacyTiles: List<Pair<Float, Int>> = listOf(
        0.0f to 0xFF2962FF.toInt(),
        0.25f to 0xFF00B0FF.toInt(),
        0.5f to 0xFF00C853.toInt(),
        0.75f to 0xFFFFD600.toInt(),
        1.0f to 0xFFFF3D00.toInt(),
    )
}
