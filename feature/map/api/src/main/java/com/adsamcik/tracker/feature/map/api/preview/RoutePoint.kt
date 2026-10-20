package com.adsamcik.tracker.feature.map.api.preview

import androidx.compose.runtime.Immutable

/**
 * Geographic point accepted by the route-preview contract.
 */
@Immutable
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    /** Points in different measured sections must never be connected. */
    val section: Int = 0,
)
