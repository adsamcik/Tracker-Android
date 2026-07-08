package com.adsamcik.tracker.stats.engine.plane

/**
 * Configuration for real-time plane/flight detection. All thresholds are tunable.
 *
 * Unlike sailing (speed-only, since a boat has no altitude signal), flight detection leans on
 * barometric altitude the same way ski detection does — but with a twist: a phone's barometer
 * reads CABIN pressure, not true outside altitude, once airborne (the cabin is pressurized to a
 * roughly constant equivalent altitude, typically ~1800-2500m). That still produces a real,
 * distinctive signature: a fast, sustained pressure-altitude RISE during the initial climb (cabin
 * pressurizing), then a long STABLE plateau during cruise, then a fast sustained FALL during
 * descent (cabin re-pressurizing toward the destination's ground level) — quite unlike anything
 * else this app tracks. GPS speed corroborates when available (it usually is during climb-out,
 * often is not at cruise altitude/airplane mode), but is never required.
 */
data class PlaneDetectionConfig(
	// Vertical rate thresholds (m/s), same physical unit as ski's, but tuned for the much faster
	// and longer-sustained cabin pressure-altitude ramps a flight produces.
	/** Vertical rate above which CLIMBING is entered. */
	val climbEnterVerticalRateMps: Float = 3.0f,
	/** Vertical rate below which CLIMBING is exited (hysteresis). */
	val climbExitVerticalRateMps: Float = 1.5f,
	/** Vertical rate below which DESCENDING is entered (negative = descending). */
	val descendEnterVerticalRateMps: Float = -3.0f,
	/** Vertical rate above which DESCENDING is exited (hysteresis). */
	val descendExitVerticalRateMps: Float = -1.5f,

	// Speed thresholds (m/s). Purely corroborating — never required, since GPS is frequently
	// unavailable at cruise altitude / in airplane mode.
	/** Sustained ground speed that alone is enough to classify CRUISING even without a preceding climb. */
	val cruiseMinSpeedMps: Float = 50f,

	// Step thresholds
	/** Step rate above which WALK is entered (steps per minute) — e.g. the terminal, or the aisle mid-flight. */
	val walkStepRateThreshold: Float = 60f,

	// Duration thresholds (milliseconds)
	/** Minimum duration a classification must hold before the confirmed state transitions. */
	val minStateDurationMs: Long = 20_000L,
	/** Cumulative airborne time (climbing + cruising + descending) before the session is confirmed as a flight. */
	val minFlightDurationForConfirmationMs: Long = 900_000L,

	// Smoothing parameters (mirrors ski's barometric smoothing)
	/** Median filter window size for barometric altitude (samples at ~1Hz). */
	val baroMedianWindow: Int = 5,
	/** Exponential moving average alpha for vertical rate smoothing. */
	val verticalRateEmaAlpha: Float = 0.3f,
)
