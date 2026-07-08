package com.adsamcik.tracker.shared.preferences.tracking

/** Immutable snapshot of tracking parameter preferences. */
data class TrackingParamsState(
    val locationEnabled: Boolean = true,
    val activityEnabled: Boolean = true,
    val stepsEnabled: Boolean = true,
    val wifiEnabled: Boolean = false,
    val cellEnabled: Boolean = false,
    val wifiNetworkEnabled: Boolean = false,
    val wifiLocationCountEnabled: Boolean = false,
    val autoTrackingMode: Int = 1,
    val transitionDetectionEnabled: Boolean = true,
    val notificationStyled: Boolean = true,
    val minDistanceMeters: Int = DEFAULT_MIN_DISTANCE,
    val minTimeSeconds: Int = DEFAULT_MIN_TIME,
    val requiredAccuracyMeters: Int = DEFAULT_REQUIRED_ACCURACY,
    val presetName: String = DEFAULT_PRESET,
    val skiDetectionEnabled: Boolean = false,
    val vehicleSpeedLimitBaselineMps: Double = DEFAULT_VEHICLE_SPEED_LIMIT_MPS,
    val sailingDetectionEnabled: Boolean = false,
    val planeDetectionEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_MIN_DISTANCE = 10
        const val DEFAULT_MIN_TIME = 2
        const val DEFAULT_REQUIRED_ACCURACY = 50
        const val DEFAULT_PRESET = "BALANCED"

        /** Minimum user-configurable baseline speed limit in km/h. */
        const val MIN_VEHICLE_SPEED_LIMIT_KMH = 30
        /** Maximum user-configurable baseline speed limit in km/h. */
        const val MAX_VEHICLE_SPEED_LIMIT_KMH = 130
        /** Default baseline speed limit in km/h (50 km/h ≈ urban driving). */
        const val DEFAULT_VEHICLE_SPEED_LIMIT_KMH = 50
        /** Default baseline speed limit in m/s. */
        const val DEFAULT_VEHICLE_SPEED_LIMIT_MPS: Double =
            DEFAULT_VEHICLE_SPEED_LIMIT_KMH / 3.6
    }

    val preset: TrackingPreset
        get() = TrackingPreset.fromName(presetName)
}
