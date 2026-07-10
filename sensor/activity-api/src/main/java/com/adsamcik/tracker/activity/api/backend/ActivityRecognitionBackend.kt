package com.adsamcik.tracker.activity.api.backend

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.stats.api.DetectedActivityType
import kotlinx.coroutines.flow.Flow

/**
 * Backend-agnostic interface for real-time activity recognition.
 * Implementations can use Google Play Services, on-device ML, or other providers.
 *
 * The post-hoc recognizers (OnFoot, Vehicle, Ski) are NOT affected by this
 * abstraction — they work on stored data, not real-time updates.
 */
interface ActivityRecognitionBackend {
	/** Human-readable name of this backend (e.g. "Google Play Services"). */
	val name: String

	/** Whether this backend is available on the current device. */
	val isAvailable: Boolean

	/**
	 * Start receiving activity updates at the given interval.
	 *
	 * @param config Recognition configuration including interval and transitions
	 * @return true if recognition was started successfully
	 */
	fun startUpdates(config: RecognitionConfig): Boolean

	/** Stop receiving all activity updates. */
	fun stopUpdates()

	/** Flow of activity updates from this backend. */
	val activityUpdates: Flow<ActivityUpdate>

	/** Flow of chronologically ordered activity-transition batches (enter/exit). */
	val transitionUpdates: Flow<List<TransitionUpdate>>

	/** Last known activity from this backend. */
	val lastActivity: RecognizedActivity

	/** Elapsed time millis of the last known activity. */
	val lastActivityElapsedTimeMillis: Long
}

/**
 * Configuration for activity recognition requests.
 *
 * @param intervalSeconds Detection interval in seconds; 0 or negative means no periodic updates
 * @param requestedTransitions Activity transitions to subscribe to
 */
data class RecognitionConfig(
	val intervalSeconds: Int,
	val requestedTransitions: Collection<ActivityTransitionData> = emptyList(),
)

/**
 * Activity detection update from the recognition backend.
 *
 * @param activity Detected activity info
 * @param elapsedTimeMillis Elapsed real-time millis at detection time
 */
data class ActivityUpdate(
	val activity: RecognizedActivity,
	val elapsedTimeMillis: Long,
)

data class RecognizedActivity(
	val type: DetectedActivityType,
	val confidence: Int,
) {
	companion object {
		val UNKNOWN = RecognizedActivity(DetectedActivityType.UNKNOWN, 0)
	}
}

/**
 * Activity transition event from the recognition backend.
 * Wraps the raw transition data without GMS dependencies.
 *
 * @param activityType Raw activity type value
 * @param transitionType Transition type (enter/exit) value
 * @param elapsedRealTimeNanos Elapsed real-time nanos of the transition
 */
data class TransitionUpdate(
	val activityType: DetectedActivityType,
	val transitionType: ActivityTransitionType,
	val elapsedRealTimeNanos: Long,
)
