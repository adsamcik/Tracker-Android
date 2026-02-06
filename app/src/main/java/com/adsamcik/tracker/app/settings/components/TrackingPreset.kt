package com.adsamcik.tracker.app.settings.components

/**
 * Tracking accuracy preset definitions.
 * Each preset configures all tracking parameters for a specific use case.
 *
 * Note: This is the legacy preset enum used by [PresetSelector].
 * Prefer [com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset] for new code.
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
