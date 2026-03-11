package com.adsamcik.tracker.shared.preferences.tracking

enum class TrackingPreset(
    val locationEnabled: Boolean,
    val wifiEnabled: Boolean,
    val cellEnabled: Boolean,
    val activityEnabled: Boolean,
    val stepsEnabled: Boolean,
    val minDistanceMeters: Int,
    val minTimeSeconds: Int,
    val requiredAccuracyMeters: Int,
) {
    HIGH_ACCURACY(
        locationEnabled = true,
        wifiEnabled = true,
        cellEnabled = true,
        activityEnabled = true,
        stepsEnabled = true,
        minDistanceMeters = 5,
        minTimeSeconds = 1,
        requiredAccuracyMeters = 100,
    ),
    BALANCED(
        locationEnabled = true,
        wifiEnabled = true,
        cellEnabled = false,
        activityEnabled = true,
        stepsEnabled = true,
        minDistanceMeters = 10,
        minTimeSeconds = 2,
        requiredAccuracyMeters = 50,
    ),
    POWER_SAVE(
        locationEnabled = true,
        wifiEnabled = false,
        cellEnabled = false,
        activityEnabled = true,
        stepsEnabled = false,
        minDistanceMeters = 30,
        minTimeSeconds = 10,
        requiredAccuracyMeters = 100,
    ),
    CUSTOM(
        locationEnabled = true,
        wifiEnabled = false,
        cellEnabled = false,
        activityEnabled = true,
        stepsEnabled = true,
        minDistanceMeters = 10,
        minTimeSeconds = 2,
        requiredAccuracyMeters = 50,
    );

    companion object {
        val DEFAULT: TrackingPreset = BALANCED

        fun fromName(name: String): TrackingPreset = when (name) {
            "BATTERY_SAVER" -> POWER_SAVE
            "HIGH_PRECISION" -> HIGH_ACCURACY
            else -> entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}
