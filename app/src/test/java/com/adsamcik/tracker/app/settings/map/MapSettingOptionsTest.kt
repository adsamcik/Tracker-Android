package com.adsamcik.tracker.app.settings.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MapSettingOptionsTest {
    @Test
    fun sliderIndexForFloatReturnsExactOptionIndex() {
        assertEquals(2f, sliderIndexForValue(1f, MapSettingOptions.quality))
    }

    @Test
    fun valueForSliderIndexClampsFloatIndices() {
        assertEquals(8f, valueForSliderIndex(99f, MapSettingOptions.quality))
    }

    @Test
    fun sliderIndexForIntReturnsExactOptionIndex() {
        assertEquals(3f, sliderIndexForValue(8, MapSettingOptions.maxHeat))
    }

    @Test
    fun valueForSliderIndexClampsIntIndices() {
        assertEquals(100, valueForSliderIndex(99f, MapSettingOptions.maxHeat))
    }

    @Test
    fun valueForSliderIndexReturnsIntOptionAtExactIndex() {
        assertEquals(10, valueForSliderIndex(4f, MapSettingOptions.maxHeat))
    }

    @Test
    fun formatVisitThresholdUsesAppropriateUnits() {
        assertEquals("45s", formatVisitThreshold(45))
        assertEquals("5 min", formatVisitThreshold(300))
        assertEquals("1h 30min", formatVisitThreshold(5400))
    }

    @Test
    fun floatOptionsRoundTripThroughSliderIndex() {
        MapSettingOptions.quality.forEach { value ->
            assertEquals(value, valueForSliderIndex(sliderIndexForValue(value, MapSettingOptions.quality), MapSettingOptions.quality))
        }
    }

    @Test
    fun intOptionsRoundTripThroughSliderIndex() {
        MapSettingOptions.maxHeat.forEach { value ->
            assertEquals(value, valueForSliderIndex(sliderIndexForValue(value, MapSettingOptions.maxHeat), MapSettingOptions.maxHeat))
        }
        MapSettingOptions.visitThresholdSeconds.forEach { value ->
            assertEquals(
                value,
                valueForSliderIndex(
                    sliderIndexForValue(value, MapSettingOptions.visitThresholdSeconds),
                    MapSettingOptions.visitThresholdSeconds,
                ),
            )
        }
    }
}
