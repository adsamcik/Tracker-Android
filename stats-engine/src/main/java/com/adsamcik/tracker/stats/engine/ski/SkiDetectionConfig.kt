package com.adsamcik.tracker.stats.engine.ski

/**
 * Configuration for ski session detection. All thresholds are tunable.
 * Default values are calibrated for typical alpine skiing conditions.
 */
data class SkiDetectionConfig(
    // Vertical rate thresholds (m/s)
    /** Vertical rate above which LIFT_UP state is entered. */
    val liftEnterVerticalRate: Float = 0.5f,
    /** Vertical rate below which LIFT_UP state is exited (hysteresis). */
    val liftExitVerticalRate: Float = 0.3f,
    /** Vertical rate below which DOWNHILL_RUN state is entered (negative = descending). */
    val downhillEnterVerticalRate: Float = -1.0f,
    /** Vertical rate above which DOWNHILL_RUN state is exited (hysteresis). */
    val downhillExitVerticalRate: Float = -0.5f,

    // Speed thresholds (m/s)
    /** Minimum speed to enter DOWNHILL_RUN. */
    val downhillEnterSpeed: Float = 3.0f,
    /** Speed below which DOWNHILL_RUN is exited (hysteresis). */
    val downhillExitSpeed: Float = 1.5f,
    /** Maximum speed for LIFT_UP (above this, likely vehicle/other). */
    val liftMaxSpeed: Float = 8.0f,
    /** Speed below which IDLE state is considered. */
    val idleSpeedThreshold: Float = 0.5f,

    // Step thresholds
    /** Step rate above which WALK state is entered (steps per minute). */
    val walkStepRateThreshold: Float = 60f,

    // Duration thresholds (milliseconds)
    /** Minimum duration in a state before transition is committed. */
    val minStateDurationMs: Long = 30_000L,

    // Session classification
    /** Minimum number of DOWNHILL_RUN + LIFT_UP cycle pairs to classify as skiing. */
    val minCyclesForClassification: Int = 2,

    // Run coalescing
    /** Max IDLE gap (ms) between DOWNHILL segments (no LIFT between) to merge as one run. */
    val maxRunCoalesceGapMs: Long = 180_000L,

    // Smoothing parameters
    /** Median filter window size for barometric altitude (samples at ~1Hz). */
    val baroMedianWindow: Int = 5,
    /** Median filter window size for GPS altitude fallback (heavier smoothing needed). */
    val gpsAltMedianWindow: Int = 11,
    /** Exponential moving average alpha for vertical rate smoothing. */
    val verticalRateEmaAlpha: Float = 0.3f
)
