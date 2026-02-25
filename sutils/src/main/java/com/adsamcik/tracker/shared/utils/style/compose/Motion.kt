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

object LoadingMotion {
    val EnterDuration = AppMotion.DurationShort   // 250ms
    val ExitDuration = AppMotion.DurationMicro    // 150ms
    val PulseDuration = 1200                       // ms, full cycle
    const val PulseAlphaMin = 0.08f
    const val PulseAlphaMax = 0.16f
}
