package com.adsamcik.tracker.app.settings.components

import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.app.settings.data.TrackingPresetSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for BatteryImpact calculation logic in [TrackingPresetSettings].
 *
 * Tests verify correct impact classification (LOW/MODERATE/HIGH) based on:
 * - Sensor enablement (location, activity, steps, WiFi, cell, barometer)
 * - GPS parameters (minTime, minDistance, requiredAccuracy)
 * - Preset configurations
 */
class BatteryImpactCalculationTest {

    // ========== Preset Verification Tests ==========

    @Test
    fun `battery saver preset should calculate LOW impact`() {
        val settings = TrackingPolicyPreset.BATTERY_SAVER.settings
        assertEquals(BatteryImpact.LOW, settings.calculateBatteryImpact())
    }

    @Test
    fun `balanced preset should calculate MODERATE impact`() {
        val settings = TrackingPolicyPreset.BALANCED.settings
        assertEquals(BatteryImpact.MODERATE, settings.calculateBatteryImpact())
    }

    @Test
    fun `high precision preset should calculate HIGH impact`() {
        val settings = TrackingPolicyPreset.HIGH_PRECISION.settings
        assertEquals(BatteryImpact.HIGH, settings.calculateBatteryImpact())
    }

    // ========== Boundary Tests ==========

    @Test
    fun `all sensors disabled should be LOW impact`() {
        val settings = TrackingPresetSettings(
            locationEnabled = false,
            requirePreciseLocation = false,
            minDistanceMeters = 100,
            minTimeSeconds = 30,
            requiredAccuracyMeters = 100,
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            barometerEnabled = false,
            useTransitionDetection = true
        )
        assertEquals(BatteryImpact.LOW, settings.calculateBatteryImpact())
    }

    @Test
    fun `all sensors enabled with aggressive params should be HIGH impact`() {
        val settings = TrackingPresetSettings(
            locationEnabled = true,
            requirePreciseLocation = true,
            minDistanceMeters = 5,
            minTimeSeconds = 5,
            requiredAccuracyMeters = 20,
            activityEnabled = true,
            stepsEnabled = true,
            wifiEnabled = true,
            cellEnabled = true,
            barometerEnabled = true,
            useTransitionDetection = false
        )
        assertEquals(BatteryImpact.HIGH, settings.calculateBatteryImpact())
    }

    @Test
    fun `location only with relaxed params should be LOW impact`() {
        val settings = TrackingPresetSettings(
            locationEnabled = true,
            requirePreciseLocation = false,
            minDistanceMeters = 100,
            minTimeSeconds = 30,
            requiredAccuracyMeters = 100,
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            barometerEnabled = false,
            useTransitionDetection = true
        )
        assertEquals(BatteryImpact.LOW, settings.calculateBatteryImpact())
    }

    // ========== Preset Battery Impact Consistency Tests ==========

    @Test
    fun `all presets should have consistent batteryImpact with calculated value`() {
        for (preset in TrackingPolicyPreset.values()) {
            val calculated = preset.settings.calculateBatteryImpact()
            assertEquals(
                "Preset ${preset.name} batteryImpact should match calculated value",
                preset.batteryImpact,
                calculated
            )
        }
    }
}
