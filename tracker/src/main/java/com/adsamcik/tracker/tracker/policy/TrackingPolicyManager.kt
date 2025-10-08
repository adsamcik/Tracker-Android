package com.adsamcik.tracker.tracker.policy

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages adaptive tracking policy state machine.
 * Decides when to collect GPS vs location-less data based on movement heuristics.
 *
 * Contract:
 * - Input: Activity transitions, step deltas, location changes
 * - Output: Current tracking policy (PASSIVE_LOW → ACTIVE_ELEVATED)
 * - State: Persisted to tracker_run table for later analysis
 *
 * Thread safety: All public methods are suspend and use internal synchronization.
 */
class TrackingPolicyManager(
	private val context: Context,
	private val isUserInitiated: Boolean,
	database: AppDatabase? = null
) {
	private val database = database ?: AppDatabase.database(context)
	private val trackerRunDao by lazy { this.database.trackerRunDao() }

	private val _currentPolicy = MutableStateFlow(
		if (isUserInitiated) TrackingPolicy.USER_INITIATED else TrackingPolicy.PASSIVE_LOW
	)

	/**
	 * Current tracking policy state.
	 */
	val currentPolicy: StateFlow<TrackingPolicy> = _currentPolicy.asStateFlow()

	private var currentRunId: Long? = null
	private var lastTransitionTime: Long = 0L

	// Movement detection state
	private var lastStepCount: Int = 0
	private var lastStepTime: Long = 0L
	private var lastActivityType: Int = -1

	/**
	 * Initialize the policy manager and start a new tracker run.
	 */
	suspend fun start() {
		val now = Time.nowMillis
		val policy = _currentPolicy.value

		val run = TrackerRun(
			startTimeMs = now,
			endTimeMs = null,
			policy = policy.name,
			policyParams = buildPolicyParams(),
			userInitiated = isUserInitiated,
			createdAt = now
		)

		currentRunId = trackerRunDao.insert(run)
		lastTransitionTime = now
	}

	/**
	 * Stop the policy manager and close the current tracker run.
	 */
	suspend fun stop() {
		val runId = currentRunId ?: return
		trackerRunDao.endRun(runId, Time.nowMillis)
		currentRunId = null
	}

	/**
	 * Check if precise location should be requested based on current policy.
	 */
	fun shouldRequestLocation(): Boolean {
		return when (_currentPolicy.value) {
			TrackingPolicy.PASSIVE_LOW -> false
			TrackingPolicy.MOVEMENT_SUSPECTED -> false // Coarse only
			TrackingPolicy.ACTIVE_MODERATE -> true
			TrackingPolicy.ACTIVE_ELEVATED -> true
			TrackingPolicy.USER_INITIATED -> true
		}
	}

	/**
	 * Update policy based on step counter delta.
	 * Called by StepDataProducer after each collection cycle.
	 */
	suspend fun onStepUpdate(stepCount: Int, timeMs: Long) {
		if (isUserInitiated) return // User sessions don't adapt

		val deltaSteps = if (lastStepCount > 0) stepCount - lastStepCount else 0
		val deltaTime = if (lastStepTime > 0) timeMs - lastStepTime else 0L

		lastStepCount = stepCount
		lastStepTime = timeMs

		// Calculate step rate (steps per minute)
		if (deltaTime > 0 && deltaSteps > 0) {
			val stepsPerMinute = (deltaSteps.toFloat() / deltaTime) * 60_000f

			// Escalation thresholds
			when (_currentPolicy.value) {
				TrackingPolicy.PASSIVE_LOW -> {
					if (stepsPerMinute > STEP_RATE_MOVEMENT_SUSPECTED) {
						transitionTo(TrackingPolicy.MOVEMENT_SUSPECTED, PolicyTransitionReason.STEP_RATE_THRESHOLD)
					}
				}
				TrackingPolicy.MOVEMENT_SUSPECTED -> {
					if (stepsPerMinute > STEP_RATE_ACTIVE_MODERATE) {
						transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.STEP_RATE_THRESHOLD)
					}
				}
				TrackingPolicy.ACTIVE_MODERATE -> {
					if (stepsPerMinute > STEP_RATE_ACTIVE_ELEVATED) {
						transitionTo(TrackingPolicy.ACTIVE_ELEVATED, PolicyTransitionReason.STEP_RATE_THRESHOLD)
					}
				}
				else -> { /* No escalation from ACTIVE_ELEVATED */ }
			}
		}

		// Cooldown logic: de-escalate if no movement for threshold duration
		checkCooldown(timeMs)
	}

	/**
	 * Update policy based on activity recognition transition.
	 * Called by ActivityDataProducer when activity changes.
	 */
	suspend fun onActivityTransition(activityType: Int, confidence: Int, timeMs: Long) {
		if (isUserInitiated) return

		val previousActivity = lastActivityType
		lastActivityType = activityType

		// Activity codes from DetectedActivity
		// 0 = IN_VEHICLE, 1 = ON_BICYCLE, 2 = ON_FOOT, 3 = STILL, 7 = WALKING, 8 = RUNNING
		val isMoving = activityType in listOf(0, 1, 2, 7, 8)
		val wasStill = previousActivity == 3

		if (wasStill && isMoving && confidence > ACTIVITY_CONFIDENCE_THRESHOLD) {
			// Transition from STILL to movement
			when (_currentPolicy.value) {
				TrackingPolicy.PASSIVE_LOW -> {
					transitionTo(TrackingPolicy.MOVEMENT_SUSPECTED, PolicyTransitionReason.ACTIVITY_TRANSITION)
				}
				TrackingPolicy.MOVEMENT_SUSPECTED -> {
					transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.ACTIVITY_TRANSITION)
				}
				else -> { /* Already elevated */ }
			}
		}
	}

	/**
	 * Update policy based on significant location change.
	 * Called when GPS fix obtained and displacement detected.
	 */
	suspend fun onLocationChange(displacementMeters: Float, timeMs: Long) {
		if (isUserInitiated) return

		// Significant movement detected → escalate
		if (displacementMeters > DISPLACEMENT_THRESHOLD_METERS) {
			when (_currentPolicy.value) {
				TrackingPolicy.PASSIVE_LOW, TrackingPolicy.MOVEMENT_SUSPECTED -> {
					transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.LOCATION_CHANGE)
				}
				TrackingPolicy.ACTIVE_MODERATE -> {
					transitionTo(TrackingPolicy.ACTIVE_ELEVATED, PolicyTransitionReason.LOCATION_CHANGE)
				}
				else -> { /* Already at max */ }
			}
		}
	}

	private suspend fun transitionTo(newPolicy: TrackingPolicy, reason: PolicyTransitionReason) {
		val oldPolicy = _currentPolicy.value
		if (oldPolicy == newPolicy) return

		val now = Time.nowMillis
		_currentPolicy.value = newPolicy

		// Close current run, start new run with updated policy
		currentRunId?.let { trackerRunDao.endRun(it, now) }

		val run = TrackerRun(
			startTimeMs = now,
			endTimeMs = null,
			policy = newPolicy.name,
			policyParams = buildPolicyParams(reason),
			userInitiated = isUserInitiated,
			createdAt = now
		)
		currentRunId = trackerRunDao.insert(run)
		lastTransitionTime = now

		// Log transition for debugging
		android.util.Log.d(
			"TrackingPolicy",
			"Transition: $oldPolicy → $newPolicy (reason: $reason)"
		)
	}

	private suspend fun checkCooldown(currentTimeMs: Long) {
		val timeSinceTransition = currentTimeMs - lastTransitionTime
		if (timeSinceTransition < COOLDOWN_DURATION_MS) return

		// De-escalate after cooldown period with no activity
		when (_currentPolicy.value) {
			TrackingPolicy.ACTIVE_ELEVATED -> {
				transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.MOVEMENT_COOLDOWN)
			}
			TrackingPolicy.ACTIVE_MODERATE -> {
				transitionTo(TrackingPolicy.MOVEMENT_SUSPECTED, PolicyTransitionReason.MOVEMENT_COOLDOWN)
			}
			TrackingPolicy.MOVEMENT_SUSPECTED -> {
				transitionTo(TrackingPolicy.PASSIVE_LOW, PolicyTransitionReason.MOVEMENT_COOLDOWN)
			}
			else -> { /* No cooldown for PASSIVE_LOW or USER_INITIATED */ }
		}
	}

	private fun buildPolicyParams(reason: PolicyTransitionReason? = null): String {
		return buildString {
			append("{")
			append("\"stepRateThresholds\":{")
			append("\"suspected\":$STEP_RATE_MOVEMENT_SUSPECTED,")
			append("\"moderate\":$STEP_RATE_ACTIVE_MODERATE,")
			append("\"elevated\":$STEP_RATE_ACTIVE_ELEVATED")
			append("},")
			append("\"cooldownMs\":$COOLDOWN_DURATION_MS,")
			append("\"displacementThreshold\":$DISPLACEMENT_THRESHOLD_METERS")
			if (reason != null) {
				append(",\"transitionReason\":\"${reason.name}\"")
			}
			append("}")
		}
	}

	companion object {
		// Step rate thresholds (steps per minute)
		private const val STEP_RATE_MOVEMENT_SUSPECTED = 10f // ~0.5 km/h walking pace
		private const val STEP_RATE_ACTIVE_MODERATE = 40f // ~3 km/h casual walking
		private const val STEP_RATE_ACTIVE_ELEVATED = 80f // ~5 km/h brisk walking/jogging

		// Activity recognition confidence threshold (0-100)
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50

		// Displacement threshold for location-based escalation (meters)
		private const val DISPLACEMENT_THRESHOLD_METERS = 50f

		// Cooldown duration before de-escalation (milliseconds)
		private const val COOLDOWN_DURATION_MS = 5 * 60 * 1000L // 5 minutes
	}
}
