package com.adsamcik.tracker.shared.preferences.map

/**
 * Legacy/shared keys that still need stable string values while map settings
 * continue migrating away from XML-backed preference definitions.
 */
object MapPreferenceKeys {
    const val LEGACY_QUALITY = "mapHeatmapQuality"
    const val LEGACY_MAX_HEAT = "mapMaxHeat"
    const val LEGACY_VISIT_THRESHOLD = "mapVisitThreshold"
    const val BASEMAP_PATH = "map.basemap.path"
}
