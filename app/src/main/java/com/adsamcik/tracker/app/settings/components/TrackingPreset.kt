package com.adsamcik.tracker.app.settings.components

/**
 * Tracking accuracy preset definitions.
 * Each preset configures all tracking parameters for a specific use case.
 */
enum class TrackingPreset {
    BATTERY_SAVER,
    BALANCED,
    HIGH_PRECISION,
    CUSTOM; // User modified advanced settings
    
    companion object {
        fun fromString(value: String?): TrackingPreset {
            return when (value) {
                "BATTERY_SAVER" -> BATTERY_SAVER
                "BALANCED" -> BALANCED
                "HIGH_PRECISION" -> HIGH_PRECISION
                "CUSTOM" -> CUSTOM
                else -> BALANCED // Default
            }
        }
    }
}

/**
 * Battery impact levels for tracking configurations.
 */
enum class BatteryImpact(val labelResId: Int, val descriptionResId: Int) {
    LOW(
        com.adsamcik.tracker.tracker.R.string.battery_impact_low,
        com.adsamcik.tracker.tracker.R.string.battery_impact_low_desc
    ),
    MODERATE(
        com.adsamcik.tracker.tracker.R.string.battery_impact_moderate,
        com.adsamcik.tracker.tracker.R.string.battery_impact_moderate_desc
    ),
    HIGH(
        com.adsamcik.tracker.tracker.R.string.battery_impact_high,
        com.adsamcik.tracker.tracker.R.string.battery_impact_high_desc
    )
}

/**
 * Preset configuration values.
 */
data class PresetConfig(
    val locationEnabled: Boolean,
    val activityEnabled: Boolean,
    val stepsEnabled: Boolean,
    val wifiEnabled: Boolean,
    val wifiNetworkEnabled: Boolean,
    val wifiLocationCountEnabled: Boolean,
    val cellEnabled: Boolean,
    val minDistance: Int,
    val minTime: Int,
    val requiredAccuracy: Int,
    val batteryImpact: BatteryImpact
) {
    companion object {
        fun forPreset(preset: TrackingPreset): PresetConfig {
            return when (preset) {
                TrackingPreset.BATTERY_SAVER -> PresetConfig(
                    locationEnabled = true,
                    activityEnabled = false,
                    stepsEnabled = false,
                    wifiEnabled = false,
                    wifiNetworkEnabled = false,
                    wifiLocationCountEnabled = false,
                    cellEnabled = false,
                    minDistance = 100,
                    minTime = 30,
                    requiredAccuracy = 100,
                    batteryImpact = BatteryImpact.LOW
                )
                TrackingPreset.BALANCED -> PresetConfig(
                    locationEnabled = true,
                    activityEnabled = true,
                    stepsEnabled = true,
                    wifiEnabled = true,
                    wifiNetworkEnabled = true,
                    wifiLocationCountEnabled = false,
                    cellEnabled = true,
                    minDistance = 20,
                    minTime = 10,
                    requiredAccuracy = 50,
                    batteryImpact = BatteryImpact.MODERATE
                )
                TrackingPreset.HIGH_PRECISION -> PresetConfig(
                    locationEnabled = true,
                    activityEnabled = true,
                    stepsEnabled = true,
                    wifiEnabled = true,
                    wifiNetworkEnabled = true,
                    wifiLocationCountEnabled = true,
                    cellEnabled = true,
                    minDistance = 5,
                    minTime = 5,
                    requiredAccuracy = 20,
                    batteryImpact = BatteryImpact.HIGH
                )
                TrackingPreset.CUSTOM -> throw IllegalArgumentException("CUSTOM preset has no predefined config")
            }
        }
        
        /**
         * Calculate battery impact based on current settings.
         */
        fun calculateBatteryImpact(
            locationEnabled: Boolean,
            activityEnabled: Boolean,
            stepsEnabled: Boolean,
            wifiEnabled: Boolean,
            cellEnabled: Boolean,
            minTime: Int,
            minDistance: Int,
            requiredAccuracy: Int
        ): BatteryImpact {
            var score = 0
            
            if (locationEnabled) score += 3
            if (minTime < 10) score += 2 // High frequency
            if (minDistance < 10) score += 2
            if (requiredAccuracy < 30) score += 1 // High precision GPS
            if (wifiEnabled) score += 1
            if (cellEnabled) score += 1
            if (activityEnabled) score += 1
            if (stepsEnabled) score += 1
            
            return when {
                score <= 4 -> BatteryImpact.LOW
                score <= 8 -> BatteryImpact.MODERATE
                else -> BatteryImpact.HIGH
            }
        }
    }
}
