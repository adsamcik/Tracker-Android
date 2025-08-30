package com.adsamcik.tracker.map.tiles

import com.adsamcik.tracker.map.data.Aggregation
import com.adsamcik.tracker.map.data.GeoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapSpecBuilderTest {

    @Test
    fun default_builder_values_are_applied() {
        val spec = heatmapSpec { }
        // Defaults from builder
        assertEquals(GeoSource.LOCATION, spec.source)
        assertEquals(Aggregation.Avg, spec.aggregation)
        assertEquals(100f, spec.maxHeat)
        assertTrue(spec.alphaFromNormalized)
        assertEquals(0.9f, spec.opacity)
        assertEquals(com.adsamcik.tracker.map.heatmap.HeatmapEngine.BASE_HEATMAP_SIZE, spec.heatmapBaseSize)
        assertTrue(spec.scaleWithQuality)
        assertEquals(64, spec.neighborNormSize)
    }

    @Test
    fun alpha_mode_internal_sets_alphaFromNormalized_false() {
        val spec = heatmapSpec {
            alphaMode = HeatmapSpecBuilder.AlphaMode.InternalAlpha
        }
        assertFalse(spec.alphaFromNormalized)
        assertEquals(0.9f, spec.opacity)
    }
}
