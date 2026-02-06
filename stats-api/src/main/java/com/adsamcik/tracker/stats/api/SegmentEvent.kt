package com.adsamcik.tracker.stats.api

/**
 * Events emitted by the session segment detector as it processes signals.
 *
 * Each variant represents a meaningful state transition in trip detection.
 */
sealed class SegmentEvent {

	/**
	 * A trip has been confirmed after the departure confirmation window.
	 *
	 * @property startTimeMs Trip start timestamp (when departure was first detected)
	 * @property triggerActivity Activity type that triggered departure, if known
	 */
	data class TripStarted(
		val startTimeMs: Long,
		val triggerActivity: DetectedActivityType?,
	) : SegmentEvent()

	/**
	 * Periodic update during an active trip with accumulated metrics.
	 *
	 * @property currentTimeMs Timestamp of this update
	 * @property accumulatedDistanceM Total distance so far in meters
	 * @property accumulatedSteps Total steps so far
	 * @property sampleCount Number of location samples collected
	 * @property currentState Current trip state (IN_TRIP or STOP_PENDING)
	 */
	data class TripUpdated(
		val currentTimeMs: Long,
		val accumulatedDistanceM: Float,
		val accumulatedSteps: Int,
		val sampleCount: Int,
		val currentState: TripState,
	) : SegmentEvent()

	/**
	 * A trip has ended with finalized metrics.
	 *
	 * @property startTimeMs Trip start timestamp
	 * @property endTimeMs Trip end timestamp
	 * @property totalDistanceM Total distance traveled in meters
	 * @property totalSteps Total step count
	 * @property sampleCount Number of location samples
	 * @property primaryActivity Most common activity type during trip
	 * @property averageActivityConfidence Average confidence of primary activity
	 * @property inferredTransportMode Classified transport mode
	 */
	data class TripEnded(
		val startTimeMs: Long,
		val endTimeMs: Long,
		val totalDistanceM: Float,
		val totalSteps: Int,
		val sampleCount: Int,
		val primaryActivity: DetectedActivityType?,
		val averageActivityConfidence: Int?,
		val inferredTransportMode: TransportMode,
	) : SegmentEvent()

	/**
	 * A departure was detected but cancelled before confirmation.
	 *
	 * @property timestampMs When the cancellation occurred
	 * @property reason Human-readable reason for cancellation
	 */
	data class DepartureCancelled(
		val timestampMs: Long,
		val reason: String,
	) : SegmentEvent()
}
