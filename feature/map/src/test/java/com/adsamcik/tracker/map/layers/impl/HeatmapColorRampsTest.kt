package com.adsamcik.tracker.map.layers.impl

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression tests for [HeatmapColorRamps].
 *
 * MapLibre evaluates `heatmap-color` for every pixel — density is >= 0 everywhere — so a
 * density-heatmap ramp whose 0.0 stop is opaque tints the entire map, including locations with no
 * collected data. The density ramps must therefore start fully transparent. The vehicle-compliance
 * palette is the exception: it is used as solid line colours, so it must stay opaque.
 */
@DisplayName("HeatmapColorRamps")
class HeatmapColorRampsTest {

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    private val densityRamps = mapOf(
        "LocationDensity" to HeatmapColorRamps.LocationDensity,
        "Speed" to HeatmapColorRamps.Speed,
    )

    @Test
    @DisplayName("density ramps start at density 0 with a fully transparent colour")
    fun densityRampsTransparentAtZero() {
        densityRamps.forEach { (name, ramp) ->
            val first = ramp.first()
            first.first shouldBe 0.0f
            check(alpha(first.second) == 0) {
                "$name must be transparent at density 0 (alpha was ${alpha(first.second)})"
            }
        }
    }

    @Test
    @DisplayName("density ramps have at least one visible (opaque) hot stop")
    fun densityRampsHaveVisibleStops() {
        densityRamps.forEach { (name, ramp) ->
            check(ramp.any { alpha(it.second) == 0xFF }) {
                "$name must have at least one fully opaque colour stop"
            }
        }
    }

    @Test
    @DisplayName("vehicle compliance colours stay opaque (rendered as solid lines)")
    fun vehicleComplianceOpaque() {
        HeatmapColorRamps.VehicleCompliance.forEach { (_, argb) ->
            check(alpha(argb) == 0xFF) { "VehicleCompliance colours must be opaque" }
        }
    }
}
