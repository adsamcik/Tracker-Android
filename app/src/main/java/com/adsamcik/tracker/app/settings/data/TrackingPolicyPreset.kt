package com.adsamcik.tracker.app.settings.data

import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact

/**
 * Predefined tracking policy presets simplifying configuration.
 * Each preset defines a complete tracking configuration optimized for specific use cases.
 *
 * Follows Apple-style philosophy:
 * - Opinionated defaults over granular toggles
 * - Clear battery/accuracy trade-offs
 * - Three tiers covering most user needs
 */
enum class TrackingPolicyPreset(
    val nameRes: Int,
    val descriptionRes: Int,
    val batteryImpact: BatteryImpact,
    val settings: TrackingPresetSettings
) {
    /**
     * Battery Saver: Maximum battery life, minimal tracking detail
     * - Coarse location only (network-based)
     * - Infrequent updates
     * - Essential sensors only
     * Use case: All-day background tracking with minimal battery impact
     */
    BATTERY_SAVER(
        nameRes = R.string.tracking_preset_battery_saver_name,
        descriptionRes = R.string.tracking_preset_battery_saver_description,
        batteryImpact = BatteryImpact.LOW,
        settings = TrackingPresetSettings(
            locationEnabled = true,
            requirePreciseLocation = false,
            minDistanceMeters = 100,
            minTimeSeconds = 30,
            requiredAccuracyMeters = 100,
            activityEnabled = true,
            stepsEnabled = false,
            wifiEnabled = false,
            cellEnabled = false,
            barometerEnabled = false,
            useTransitionDetection = true
        )
    ),

    /**
     * Balanced: Good accuracy with reasonable battery usage (default)
     * - Precise location
     * - Moderate update frequency
     * - Most sensors enabled
     * Use case: Regular tracking sessions with good route detail
     */
    BALANCED(
        nameRes = R.string.tracking_preset_balanced_name,
        descriptionRes = R.string.tracking_preset_balanced_description,
        batteryImpact = BatteryImpact.MODERATE,
        settings = TrackingPresetSettings(
            locationEnabled = true,
            requirePreciseLocation = true,
            minDistanceMeters = 20,
            minTimeSeconds = 10,
            requiredAccuracyMeters = 50,
            activityEnabled = true,
            stepsEnabled = true,
            wifiEnabled = true,
            cellEnabled = false,
            barometerEnabled = true,
            useTransitionDetection = true
        )
    ),

    /**
     * High Precision: Maximum detail, higher battery consumption
     * - Precise location with high accuracy threshold
     * - Frequent updates
     * - All sensors enabled
     * Use case: Detailed route mapping, challenges, competitions
     */
    HIGH_PRECISION(
        nameRes = R.string.tracking_preset_high_precision_name,
        descriptionRes = R.string.tracking_preset_high_precision_description,
        batteryImpact = BatteryImpact.HIGH,
        settings = TrackingPresetSettings(
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
            useTransitionDetection = false // Continuous tracking for max detail
        )
    );

    companion object {
        /**
         * Default preset for new users
         */
        val DEFAULT = BALANCED

        /**
         * Detect which preset matches current settings, or return null for custom
         */
        fun fromSettings(settings: TrackingPresetSettings): TrackingPolicyPreset? {
            return values().firstOrNull { it.settings == settings }
        }
    }
}

/**
 * Complete tracking configuration settings.
 * Used to apply preset settings or represent custom configurations.
 */
data class TrackingPresetSettings(
    // Location settings
    val locationEnabled: Boolean,
    val requirePreciseLocation: Boolean,
    val minDistanceMeters: Int,
    val minTimeSeconds: Int,
    val requiredAccuracyMeters: Int,

    // Activity & sensors
    val activityEnabled: Boolean,
    val stepsEnabled: Boolean,
    val wifiEnabled: Boolean,
    val cellEnabled: Boolean,
    val barometerEnabled: Boolean,

    // Advanced options
    val useTransitionDetection: Boolean
)
