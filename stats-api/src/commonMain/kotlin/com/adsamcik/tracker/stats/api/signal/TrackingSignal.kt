package com.adsamcik.tracker.stats.api.signal

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.value.*

/**
 * Unified per-cycle signal from the tracker service.
 * Each tracking cycle produces exactly one TrackingSignal.
 * Nullable fields indicate the sensor was unavailable that cycle.
 */
data class TrackingSignal(
	val timestampMs: EpochMs,
	val location: LocationSignal? = null,
	val activity: ActivitySignal? = null,
	val steps: StepSignal? = null,
)

/** GPS location data for a single cycle. */
data class LocationSignal(
	val coordinate: CoordinateE7,
	val horizontalAccuracyM: Float,
	val speed: SpeedMps?,
	val altitudeM: Float? = null,
	val distanceDelta: DistanceM? = null,
)

/** Activity recognition data for a single cycle. */
data class ActivitySignal(
	val type: DetectedActivityType,
	val confidence: ActivityConfidence,
)

/** Step counter data for a single cycle. */
data class StepSignal(
	val stepDelta: StepCount,
	val totalStepsSinceBoot: Long,
)
