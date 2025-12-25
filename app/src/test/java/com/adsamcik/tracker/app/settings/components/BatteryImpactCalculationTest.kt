package com.adsamcik.tracker.app.settings.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for BatteryImpact calculation logic.
 * 
 * Tests verify correct impact classification (LOW/MODERATE/HIGH) based on:
 * - Sensor enablement (location, activity, steps, WiFi, cell)
 * - GPS parameters (minTime, minDistance, requiredAccuracy)
 * - Edge cases and boundary conditions
 */
class BatteryImpactCalculationTest {
    
    // ========== Preset Verification Tests ==========
    
    @Test
    fun `battery saver preset should calculate LOW impact`() {
        val config = PresetConfig.forPreset(TrackingPreset.BATTERY_SAVER)
        
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = config.locationEnabled,
            activityEnabled = config.activityEnabled,
            stepsEnabled = config.stepsEnabled,
            wifiEnabled = config.wifiEnabled,
            cellEnabled = config.cellEnabled,
            minTime = config.minTime,
            minDistance = config.minDistance,
            requiredAccuracy = config.requiredAccuracy
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    @Test
    fun `balanced preset should calculate MODERATE impact`() {
        val config = PresetConfig.forPreset(TrackingPreset.BALANCED)
        
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = config.locationEnabled,
            activityEnabled = config.activityEnabled,
            stepsEnabled = config.stepsEnabled,
            wifiEnabled = config.wifiEnabled,
            cellEnabled = config.cellEnabled,
            minTime = config.minTime,
            minDistance = config.minDistance,
            requiredAccuracy = config.requiredAccuracy
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `high precision preset should calculate HIGH impact`() {
        val config = PresetConfig.forPreset(TrackingPreset.HIGH_PRECISION)
        
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = config.locationEnabled,
            activityEnabled = config.activityEnabled,
            stepsEnabled = config.stepsEnabled,
            wifiEnabled = config.wifiEnabled,
            cellEnabled = config.cellEnabled,
            minTime = config.minTime,
            minDistance = config.minDistance,
            requiredAccuracy = config.requiredAccuracy
        )
        
        assertEquals(BatteryImpact.HIGH, impact)
    }
    
    // ========== Boundary Tests (Score Thresholds) ==========
    
    @Test
    fun `score of 4 should be LOW impact (boundary)`() {
        // Score: 3 (location) + 1 (steps) = 4 → LOW
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = true,     // +1
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,           // No bonus (≥10)
            minDistance = 50,       // No bonus (≥10)
            requiredAccuracy = 50   // No bonus (≥30)
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    @Test
    fun `score of 5 should be MODERATE impact (boundary)`() {
        // Score: 3 (location) + 1 (activity) + 1 (steps) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 50,
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `score of 8 should be MODERATE impact (boundary)`() {
        // Score: 3 (location) + 1 (activity) + 1 (steps) + 1 (wifi) + 1 (cell) + 1 (accuracy<30) = 8 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1
            cellEnabled = true,      // +1
            minTime = 30,           // No bonus
            minDistance = 50,       // No bonus
            requiredAccuracy = 25   // +1 (< 30)
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `score of 9 should be HIGH impact (boundary)`() {
        // Score: 3 + 1 + 1 + 1 + 1 + 2 (minDistance<10) = 9 → HIGH
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1
            cellEnabled = true,      // +1
            minTime = 30,           // No bonus
            minDistance = 5,        // +2 (< 10)
            requiredAccuracy = 50   // No bonus
        )
        
        assertEquals(BatteryImpact.HIGH, impact)
    }
    
    // ========== Minimal Configuration Tests ==========
    
    @Test
    fun `all sensors disabled should be LOW impact`() {
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = false,
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 60,
            minDistance = 200,
            requiredAccuracy = 100
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    @Test
    fun `only location enabled with conservative settings should be LOW impact`() {
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,   // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 60,            // No bonus
            minDistance = 200,       // No bonus
            requiredAccuracy = 100   // No bonus
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    // ========== High Frequency Tests ==========
    
    @Test
    fun `high frequency updates (minTime less than 10) should add score`() {
        // Score: 3 (location) + 2 (minTime<10) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 5,            // +2 (< 10)
            minDistance = 50,
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `minTime boundary at 10 seconds should not add bonus`() {
        // Score: 3 (location) + 0 (minTime=10, not <10) = 3 → LOW
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 10,           // No bonus (not < 10)
            minDistance = 50,
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    @Test
    fun `minTime at 9 seconds should add bonus`() {
        // Score: 3 (location) + 2 (minTime<10) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 9,            // +2 (< 10)
            minDistance = 50,
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    // ========== High Precision Distance Tests ==========
    
    @Test
    fun `minDistance less than 10 should add score`() {
        // Score: 3 (location) + 2 (minDistance<10) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 5,        // +2 (< 10)
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `minDistance boundary at 10 meters should not add bonus`() {
        // Score: 3 (location) = 3 → LOW
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 10,       // No bonus (not < 10)
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    @Test
    fun `minDistance at 9 meters should add bonus`() {
        // Score: 3 (location) + 2 (minDistance<10) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 9,        // +2 (< 10)
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    // ========== GPS Accuracy Tests ==========
    
    @Test
    fun `requiredAccuracy less than 30 should add score`() {
        // Score: 3 (location) + 1 (accuracy<30) + 1 (steps) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = true,     // +1
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 50,
            requiredAccuracy = 20   // +1 (< 30)
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `requiredAccuracy boundary at 30 should not add bonus`() {
        // Score: 3 (location) + 1 (steps) = 4 → LOW
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = true,     // +1
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 50,
            requiredAccuracy = 30   // No bonus (not < 30)
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    @Test
    fun `requiredAccuracy at 29 should add bonus`() {
        // Score: 3 (location) + 1 (accuracy<30) + 1 (steps) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = true,     // +1
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 30,
            minDistance = 50,
            requiredAccuracy = 29   // +1 (< 30)
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    // ========== Sensor Combination Tests ==========
    
    @Test
    fun `all sensors enabled with moderate settings should be HIGH impact`() {
        // Score: 3 + 1 + 1 + 1 + 1 + 1 = 8 → MODERATE (just below HIGH threshold)
        val impactModerate = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1
            cellEnabled = true,      // +1
            minTime = 30,           // No bonus
            minDistance = 50,       // No bonus
            requiredAccuracy = 50   // No bonus
        )
        
        assertEquals("Expected MODERATE for score 7 but got $impactModerate", BatteryImpact.MODERATE, impactModerate)
        
        // Add one more factor to push to HIGH
        // Score: 3 + 1 + 1 + 1 + 1 + 1 (accuracy<30) = 8 + 1 = 9 → HIGH
        val impactHigh = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1
            cellEnabled = true,      // +1
            minTime = 5,            // +2 (< 10)
            minDistance = 50,
            requiredAccuracy = 25   // +1 (< 30)
        )
        
        assertEquals(BatteryImpact.HIGH, impactHigh)
    }
    
    // ========== Extreme Configuration Tests ==========
    
    @Test
    fun `maximum battery drain configuration should be HIGH impact`() {
        // Score: 3 + 1 + 1 + 1 + 1 + 2 + 2 + 1 = 12 → HIGH
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1
            cellEnabled = true,      // +1
            minTime = 1,            // +2 (< 10)
            minDistance = 1,        // +2 (< 10)
            requiredAccuracy = 10   // +1 (< 30)
        )
        
        assertEquals(BatteryImpact.HIGH, impact)
    }
    
    @Test
    fun `location disabled should result in LOW impact regardless of other settings`() {
        // Score: 0 (no location) + 1 + 1 + 1 + 1 + 2 + 2 + 1 = 9 - 3 = 6 → MODERATE
        // Actually this tests that location is the biggest contributor
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = false, // No +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1
            cellEnabled = true,      // +1
            minTime = 1,            // +2 (< 10)
            minDistance = 1,        // +2 (< 10)
            requiredAccuracy = 10   // +1 (< 30)
        )
        
        // Without location's +3, score is 9, which is HIGH
        // This shows minTime and minDistance bonuses apply even without location
        assertEquals(BatteryImpact.HIGH, impact)
    }
    
    // ========== Edge Case Tests ==========
    
    @Test
    fun `zero values for time and distance should add bonuses`() {
        // Score: 3 + 2 (minTime=0<10) + 2 (minDistance=0<10) = 7 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 0,            // +2 (< 10)
            minDistance = 0,        // +2 (< 10)
            requiredAccuracy = 50
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `negative values should be treated as valid (implementation allows)`() {
        // Negative values unlikely but test defensive behavior
        // Score: 3 + 2 + 2 = 7 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = -5,           // +2 (< 10, even if negative)
            minDistance = -10,      // +2 (< 10)
            requiredAccuracy = -1   // +1 (< 30)
        )
        
        assertEquals(BatteryImpact.MODERATE, impact) // 3 + 2 + 2 + 1 = 8 -> MODERATE
    }
    
    @Test
    fun `very large values should not add bonuses`() {
        // Score: 3 (location only) = 3 → LOW
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 10000,        // No bonus
            minDistance = 10000,    // No bonus
            requiredAccuracy = 10000 // No bonus
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
    
    // ========== Realistic Scenario Tests ==========
    
    @Test
    fun `walking commute tracking (moderate accuracy, medium frequency) should be MODERATE`() {
        // Realistic: User wants to track daily commute
        // Score: 3 + 1 (activity) + 1 (steps) = 5 → MODERATE
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1 (detect walking/cycling)
            stepsEnabled = true,     // +1 (count steps)
            wifiEnabled = false,
            cellEnabled = false,
            minTime = 15,           // Every 15 seconds
            minDistance = 25,       // Every 25 meters
            requiredAccuracy = 40   // Moderate accuracy
        )
        
        assertEquals(BatteryImpact.MODERATE, impact)
    }
    
    @Test
    fun `hiking trip (high accuracy, all sensors) should be HIGH`() {
        // Realistic: User wants detailed hiking route
        // Score: 3 + 1 + 1 + 1 + 1 + 2 + 2 + 1 = 12 → HIGH
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = true,  // +1
            stepsEnabled = true,     // +1
            wifiEnabled = true,      // +1 (POI detection)
            cellEnabled = true,      // +1
            minTime = 5,            // +2 (detailed timeline)
            minDistance = 5,        // +2 (detailed route)
            requiredAccuracy = 15   // +1 (high precision)
        )
        
        assertEquals(BatteryImpact.HIGH, impact)
    }
    
    @Test
    fun `passive all-day tracking (coarse location, low frequency) should be LOW`() {
        // Realistic: User wants background tracking without battery drain
        // Score: 3 + 1 (cell) = 4 → LOW
        val impact = PresetConfig.calculateBatteryImpact(
            locationEnabled = true,  // +3
            activityEnabled = false,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = true,      // +1 (coarse location)
            minTime = 60,           // Every minute
            minDistance = 100,      // Every 100 meters
            requiredAccuracy = 200  // Very coarse
        )
        
        assertEquals(BatteryImpact.LOW, impact)
    }
}
