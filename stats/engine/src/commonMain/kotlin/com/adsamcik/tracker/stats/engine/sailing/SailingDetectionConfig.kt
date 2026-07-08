package com.adsamcik.tracker.stats.engine.sailing

/**
 * Configuration for real-time sailing/boating detection. All thresholds are tunable.
 * Default values are calibrated for typical small-boat sailing conditions.
 *
 * Unlike ski detection (which leans on barometric vertical rate), sailing has no reliable
 * altitude signal to exploit — a boat's motion is essentially 2D. Detection is therefore
 * speed- and step-rate based only: "moving at a speed consistent with sailing, without the
 * step cadence of walking". This is a real, documented limitation: cycling and slow driving
 * overlap the same speed range, so this heuristic cannot distinguish "sailing" from "any other
 * silent, wheel-free mode of travel in the same speed band" on speed alone. It is intended as a
 * lightweight signal (auto-tagging + GPS tier adaptation), not a certainty.
 */
data class SailingDetectionConfig(
	// Speed thresholds (m/s)
	/** Minimum speed to enter SAILING (below this the boat is considered moored/becalmed/idle). */
	val sailingEnterMinSpeedMps: Float = 0.75f,
	/** Speed below which SAILING is exited back to IDLE (hysteresis; lower than the enter threshold). */
	val sailingExitMinSpeedMps: Float = 0.4f,
	/** Maximum speed for SAILING. Above this, more likely a powered vessel/vehicle. */
	val sailingMaxSpeedMps: Float = 15.0f,

	// Step thresholds
	/** Step rate above which WALK is entered (steps per minute) — e.g. walking the dock/shore. */
	val walkStepRateThreshold: Float = 40f,

	// Duration thresholds (milliseconds)
	/** Minimum duration a classification must hold before the confirmed state transitions. */
	val minStateDurationMs: Long = 45_000L,
	/** Cumulative time spent in SAILING before the session is confirmed as sailing. */
	val minSailingDurationForConfirmationMs: Long = 600_000L,

	// Smoothing
	/** Median filter window size for GPS speed (samples), reduces wave/GPS-jitter-induced flicker. */
	val speedMedianWindow: Int = 5,
)
