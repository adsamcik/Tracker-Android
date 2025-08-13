package com.adsamcik.tracker.map.v2

/** Dev / migration feature flags for map v2 path. */
object DevFlags {
    // Enable repository-backed data fetch for Location heatmap (Phase 3.5 optional)
    // Default false to preserve legacy behavior until broader QA completes.
    val USE_REPO_LOCATION_HEATMAP: Boolean = false // set to BuildConfig.DEBUG when ready
}
