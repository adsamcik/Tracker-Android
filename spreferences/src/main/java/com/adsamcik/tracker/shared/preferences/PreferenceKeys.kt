package com.adsamcik.tracker.shared.preferences

object PreferenceKeys {
    const val ACTIVITY_ENABLED = "trackingActivityEnabled"
    const val ACTIVITY_ENABLED_DEFAULT = true

    const val AUTO_TRACKING_TRANSITION_ENABLED = "transitionApiEnabled"
    const val AUTO_TRACKING_TRANSITION_ENABLED_DEFAULT = true

    const val BAROMETER_ENABLED = "trackingBarometerEnabled"
    const val BAROMETER_ENABLED_DEFAULT = true

    const val CELL_ENABLED = "trackingCellEnabled"
    const val CELL_ENABLED_DEFAULT = false

    const val ERROR_REPORTING = "errorReporting"
    const val ERROR_REPORTING_DEFAULT = false

    const val LENGTH_SYSTEM = "lengthSystem"
    const val LENGTH_SYSTEM_DEFAULT = "Metric"

    const val LOCATION_ENABLED = "trackingLocationEnabled"
    const val LOCATION_ENABLED_DEFAULT = true

    const val LOG_ACTIVITY = "log_activity"
    const val LOG_ACTIVITY_DEFAULT = false

    const val LOG_GAMES = "log_games"
    const val LOG_GAMES_DEFAULT = false

    const val NOTIFICATION_STYLED = "notificationStyle"
    const val NOTIFICATION_STYLED_DEFAULT = true

    const val SKI_INFRASTRUCTURE_ENABLED = "ski_infrastructure_enabled"
    const val SKI_INFRASTRUCTURE_ENABLED_DEFAULT = false

    const val SPEED_FORMAT = "speedFormat"
    const val SPEED_FORMAT_DEFAULT = "Hour"

    const val STEPS_ENABLED = "trackingStepsEnabled"
    const val STEPS_ENABLED_DEFAULT = true

    const val TRACKER_TIMER = "trackerTicker"

    const val TRACKING_ACTIVITY_MODE = "backgroundTracking"
    const val TRACKING_ACTIVITY_MODE_DEFAULT = 1

    const val TRACKING_MIN_DISTANCE = "minTrackingDistance"
    const val TRACKING_MIN_DISTANCE_DEFAULT = 10

    const val TRACKING_MIN_TIME = "minTrackingTimeDifference"
    const val TRACKING_MIN_TIME_DEFAULT = 2

    const val TRACKING_REQUIRED_ACCURACY = "requiredTrackingAccuracy"
    const val TRACKING_REQUIRED_ACCURACY_DEFAULT = 50

    const val WIFI_ENABLED = "trackingWifiEnabled"
    const val WIFI_ENABLED_DEFAULT = false

    const val WIFI_LOCATION_COUNT_ENABLED = "trackingWifiLocationCountEnabled"
    const val WIFI_LOCATION_COUNT_ENABLED_DEFAULT = false

    const val WIFI_NETWORK_ENABLED = "trackingWifiNetworkEnabled"
    const val WIFI_NETWORK_ENABLED_DEFAULT = false
}
