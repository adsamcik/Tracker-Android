package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

object AppMotion {
    // Spring Configurations
    val SecureSnap = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val TactileActive = spring<Float>(
        dampingRatio = 0.65f,
        stiffness = Spring.StiffnessMediumLow
    )

    val SpatialGlide = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessLow
    )

    // Durations
    const val DurationMicro = 150
    const val DurationShort = 250
    const val DurationMedium = 400
    const val DurationLong = 600
}
