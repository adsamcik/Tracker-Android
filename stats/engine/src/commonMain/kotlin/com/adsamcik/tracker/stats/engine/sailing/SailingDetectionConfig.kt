package com.adsamcik.tracker.stats.engine.sailing

/**
 * Configuration for real-time sailing/boating detection. All thresholds are tunable.
 * Default values are calibrated for typical small-boat sailing conditions.
 *
 * Unlike ski detection (which leans on barometric vertical rate), sailing has no reliable
 * altitude signal to exploit — a boat's motion is essentially 2D. Detection is therefore
 * speed- and step-rate based only: "moving at a speed consistent with sailing, without the
 * step cadence of walking". Cycling and slow driving overlap the same speed range, so speed alone
 * is retained only as [SailingDetectionReason.BOAT_LIKE_MOTION]. It can become SAILING only when
 * the pipeline has current activity-recognition context that does not strongly indicate a bicycle
 * or vehicle. No water or coastline context is available to this detector.
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
	/**
	 * Maximum interval between accepted GPS samples. A 30-second limit is three 10-second
	 * collection intervals: longer gaps are represented as UNKNOWN rather than bridged.
	 */
	val maxSampleGapMs: Long = 30_000L,

	// Smoothing
	/** Median filter window size for GPS speed (samples), reduces wave/GPS-jitter-induced flicker. */
	val speedMedianWindow: Int = 5,
)
