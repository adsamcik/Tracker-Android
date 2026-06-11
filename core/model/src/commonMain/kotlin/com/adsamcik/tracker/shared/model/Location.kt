package com.adsamcik.tracker.shared.model

/**
 * Room-free representation of a captured geographic fix shared across module boundaries.
 */
data class Location(
val time: Long,
val latitude: Double,
val longitude: Double,
val altitude: Double?,
val horizontalAccuracy: Float?,
val verticalAccuracy: Float?,
val speed: Float?,
val speedAccuracy: Float?,
)
