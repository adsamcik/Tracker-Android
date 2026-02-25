package com.adsamcik.tracker.stats.engine.filter

/**
 * Configuration for GPS track cleaning pipeline.
 * Defaults are tuned for general outdoor tracking at ~1Hz.
 */
data class GpsCleaningConfig(
	/** Drop points with horizontal accuracy worse than this (meters). */
	val accuracyGateM: Float = 80f,
	/** Maximum plausible speed (m/s). Points exceeding this are spike-removed. */
	val maxSpeedMps: Float = 50f,
	/** Maximum plausible acceleration (m/s²). Sustained above this is a spike. */
	val maxAccelerationMps2: Float = 5f,
	/** Median filter window size for lat/lon smoothing. */
	val positionMedianWindow: Int = 3,
	/** Median filter window size for altitude smoothing. */
	val altitudeMedianWindow: Int = 5,
	/** Time gap (ms) that triggers a new segment. */
	val segmentGapMs: Long = 120_000L,
	/** Minimum points per segment to keep. */
	val minSegmentPoints: Int = 3
)
