package com.adsamcik.tracker.feature.map.api.navigation

import kotlinx.serialization.Serializable

@Serializable
data object Map

@Serializable
data class MapTripContext(
    val tripId: Long,
    val startMs: Long,
    val endMs: Long,
)
