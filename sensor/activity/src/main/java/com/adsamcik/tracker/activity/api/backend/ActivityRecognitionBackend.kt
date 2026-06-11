package com.adsamcik.tracker.activity.api.backend

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.shared.base.data.ActivityInfo
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

	/** Flow of activity transition events (enter/exit). */
	val transitionUpdates: Flow<TransitionUpdate>

	/** Last known activity from this backend. */
	val lastActivity: ActivityInfo

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
	val activity: ActivityInfo,
	val elapsedTimeMillis: Long,
)

/**
 * Activity transition event from the recognition backend.
 * Wraps the raw transition data without GMS dependencies.
 *
 * @param activityType Raw activity type value
 * @param transitionType Transition type (enter/exit) value
 * @param elapsedRealTimeNanos Elapsed real-time nanos of the transition
 */
data class TransitionUpdate(
	val activityType: Int,
	val transitionType: Int,
	val elapsedRealTimeNanos: Long,
)
