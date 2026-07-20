package com.adsamcik.tracker.tracker.policy

import android.content.Context
import com.adsamcik.tracker.tracker.engine.BuildConfig
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.TrackerRun
import com.adsamcik.tracker.stats.api.PolicyTier
import com.adsamcik.tracker.stats.engine.policy.DefaultPolicyEscalationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages adaptive tracking policy state machine.
 * Decides when to collect GPS vs location-less data based on movement heuristics.
 *
 * When [escalationEngine] is provided, all signal processing delegates to
 * the new 4-tier engine. The engine's [PolicyTier] output is mapped to
 * [TrackingPolicy] via [PolicyTierMapper] for backward compatibility with
 * existing components (timer, pre-components, etc.).
 *
 * When [escalationEngine] is null, falls back to the legacy built-in
 * state machine (step rate thresholds + cooldown).
 *
 * Thread safety: All public methods are suspend and use internal synchronization.
 *
 * @param context Android context for database access.
 * @param isUserInitiated Whether tracking was initiated by user action.
 * @param escalationEngine Optional new-style engine for signal processing.
 * @param scope Coroutine scope for observing engine state changes.
 * @param database Optional database instance for testability.
 */
class TrackingPolicyManager(
	private val context: Context,
	private val isUserInitiated: Boolean,
	val escalationEngine: DefaultPolicyEscalationEngine? = null,
	private val scope: CoroutineScope? = null,
	database: AppDatabase? = null,
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val initialTier: PolicyTier = if (isUserInitiated) {
		PolicyTier.PRECISION
	} else {
		PolicyTier.AMBIENT
	},
) {
	private val database = database ?: AppDatabase.database(context)
	private val trackerRunDao by lazy { this.database.trackerRunDao() }

	private val stateMutex = Mutex()

	private val _currentPolicy = MutableStateFlow(
		if (isUserInitiated) {
			TrackingPolicy.USER_INITIATED
		} else {
			PolicyTierMapper.toTrackingPolicy(initialTier)
		}
	)

	/**
	 * Current tracking policy state.
	 */
	val currentPolicy: StateFlow<TrackingPolicy> = _currentPolicy.asStateFlow()

	private var currentRunId: Long? = null
	private var lastTransitionTime: Long = 0L

	// Legacy movement detection state (used only when engine is null)
	private var lastStepCount: Int = 0
	private var lastStepTime: Long = 0L
	private var lastActivityType: Int = -1

	// Engine observation job
	private var engineObservationJob: Job? = null

	/**
	 * Initialize the policy manager and start a new tracker run.
	 */
	suspend fun start() = stateMutex.withLock {
		val now = Time.nowMillis
		val policy = _currentPolicy.value

		val run = TrackerRun(
			startTimeMs = now,
			endTimeMs = null,
			policy = policy.name,
			policyParams = buildPolicyParams(policy = policy),
			userInitiated = isUserInitiated,
			createdAt = now,
		)

		currentRunId = withContext(dispatchers.io) {
			// A process can die before stop() closes its run. Reconcile every orphan first so an
			// open-ended historical run cannot be interpreted as continuous presence up to now.
			trackerRunDao.closeOpenRuns(now)
			trackerRunDao.insert(run)
		}
		lastTransitionTime = now

		// Start the escalation engine and observe tier changes
		escalationEngine?.let { engine ->
			engine.start(initialTier, now)

			engineObservationJob = scope?.launch {
				engine.policyState.collect { state ->
					val newPolicy = if (isUserInitiated) {
						TrackingPolicy.USER_INITIATED
					} else {
						PolicyTierMapper.toTrackingPolicy(state.tier)
					}
					// Use current time, not captured start time (C5 fix)
					onEngineStateChanged(newPolicy, state.transitionReason.name, Time.nowMillis)
				}
			}
		}
	}

	/**
	 * Stop the policy manager and close the current tracker run.
	 */
	suspend fun stop() = stateMutex.withLock {
		if (BuildConfig.DEBUG) {
			android.util.Log.d("TrackerQA", "TrackingPolicyManager.stop(): currentRunId=$currentRunId")
		}
		engineObservationJob?.cancel()
		engineObservationJob = null
		escalationEngine?.stop()

		val runId = currentRunId ?: return@withLock
		trackerRunDao.endRun(runId, Time.nowMillis)
		if (BuildConfig.DEBUG) {
			android.util.Log.d("TrackerQA", "TrackingPolicyManager.stop(): endRun called for runId=$runId")
		}
		currentRunId = null
	}

	/**
	 * Check if precise location should be requested based on current policy.
	 */
	fun shouldRequestLocation(): Boolean {
		return when (_currentPolicy.value) {
			TrackingPolicy.PASSIVE_LOW -> false
			TrackingPolicy.MOVEMENT_SUSPECTED -> false
			TrackingPolicy.ACTIVE_MODERATE -> true
			TrackingPolicy.ACTIVE_ELEVATED -> true
			TrackingPolicy.USER_INITIATED -> true
		}
	}

	/**
	 * Update policy based on step counter delta.
	 */
	suspend fun onStepUpdate(stepCount: Int, timeMs: Long) {
		escalationEngine?.let { engine ->
			// Delegate to engine (it handles cumulative step count as Long)
			engine.onStepCount(stepCount.toLong(), timeMs)
			return
		}

		// Legacy path
		stateMutex.withLock {
			if (isUserInitiated) return@withLock

			val deltaSteps = if (lastStepCount > 0) stepCount - lastStepCount else 0
			val deltaTime = if (lastStepTime > 0) timeMs - lastStepTime else 0L

			lastStepCount = stepCount
			lastStepTime = timeMs

			if (deltaTime > 0 && deltaSteps > 0) {
				val stepsPerMinute = (deltaSteps.toFloat() / deltaTime) * 60_000f

				when (_currentPolicy.value) {
					TrackingPolicy.PASSIVE_LOW -> {
						if (stepsPerMinute > STEP_RATE_MOVEMENT_SUSPECTED) {
							transitionTo(TrackingPolicy.MOVEMENT_SUSPECTED, PolicyTransitionReason.STEP_RATE_THRESHOLD, timeMs)
						}
					}
					TrackingPolicy.MOVEMENT_SUSPECTED -> {
						if (stepsPerMinute > STEP_RATE_ACTIVE_MODERATE) {
							transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.STEP_RATE_THRESHOLD, timeMs)
						}
					}
					TrackingPolicy.ACTIVE_MODERATE -> {
						if (stepsPerMinute > STEP_RATE_ACTIVE_ELEVATED) {
							transitionTo(TrackingPolicy.ACTIVE_ELEVATED, PolicyTransitionReason.STEP_RATE_THRESHOLD, timeMs)
						}
					}
					else -> { /* No escalation from ACTIVE_ELEVATED */ }
				}
			}

			checkCooldown(timeMs)
		}
	}

	/**
	 * Update policy based on activity recognition transition.
	 */
	suspend fun onActivityTransition(activityType: Int, confidence: Int, timeMs: Long) {
		escalationEngine?.let { engine ->
			val activity = PolicyTierMapper.toDetectedActivityType(activityType)
			engine.onActivityDetected(activity, confidence, timeMs)
			return
		}

		// Legacy path
		stateMutex.withLock {
			if (isUserInitiated) return@withLock

			val previousActivity = lastActivityType
			lastActivityType = activityType

			val isMoving = activityType in listOf(0, 1, 2, 7, 8)
			val wasStill = previousActivity == 3

			if (wasStill && isMoving && confidence > ACTIVITY_CONFIDENCE_THRESHOLD) {
				when (_currentPolicy.value) {
					TrackingPolicy.PASSIVE_LOW -> {
						transitionTo(TrackingPolicy.MOVEMENT_SUSPECTED, PolicyTransitionReason.ACTIVITY_TRANSITION, timeMs)
					}
					TrackingPolicy.MOVEMENT_SUSPECTED -> {
						transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.ACTIVITY_TRANSITION, timeMs)
					}
					else -> { /* Already elevated */ }
				}
			}
		}
	}

	/**
	 * Update policy based on significant location change.
	 */
	suspend fun onLocationChange(displacementMeters: Float, timeMs: Long) {
		escalationEngine?.let { engine ->
			if (displacementMeters > DISPLACEMENT_THRESHOLD_METERS) {
				engine.onSignificantMotion(timeMs)
			}
			return
		}

		stateMutex.withLock {
			if (isUserInitiated) return@withLock

			if (displacementMeters > DISPLACEMENT_THRESHOLD_METERS) {
				when (_currentPolicy.value) {
					TrackingPolicy.PASSIVE_LOW, TrackingPolicy.MOVEMENT_SUSPECTED -> {
						transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.LOCATION_CHANGE, timeMs)
					}
					TrackingPolicy.ACTIVE_MODERATE -> {
						transitionTo(TrackingPolicy.ACTIVE_ELEVATED, PolicyTransitionReason.LOCATION_CHANGE, timeMs)
					}
					else -> { /* Already at max */ }
				}
			}
		}
	}

	/**
	 * Called when the escalation engine emits a new state.
	 * Maps to [TrackingPolicy] and persists to tracker_run.
	 */
	private suspend fun onEngineStateChanged(
		newPolicy: TrackingPolicy,
		reasonName: String,
		timeMs: Long,
	) = stateMutex.withLock {
		val oldPolicy = _currentPolicy.value
		if (oldPolicy == newPolicy) return@withLock

		_currentPolicy.value = newPolicy

		// Persist transition to tracker_run
		currentRunId?.let { trackerRunDao.endRun(it, timeMs) }

		val run = TrackerRun(
			startTimeMs = timeMs,
			endTimeMs = null,
			policy = newPolicy.name,
			policyParams = buildEngineParams(policy = newPolicy, reasonName = reasonName),
			userInitiated = isUserInitiated,
			createdAt = timeMs,
		)
		currentRunId = withContext(dispatchers.io) {
			trackerRunDao.insert(run)
		}
		lastTransitionTime = timeMs

		if (BuildConfig.DEBUG) {
			android.util.Log.d(
				"TrackingPolicy",
				"Engine transition: $oldPolicy -> $newPolicy (reason: $reasonName)"
			)
		}
	}

	private suspend fun transitionTo(newPolicy: TrackingPolicy, reason: PolicyTransitionReason, timeMs: Long) {
		val oldPolicy = _currentPolicy.value
		if (oldPolicy == newPolicy) return

		_currentPolicy.value = newPolicy

		currentRunId?.let { trackerRunDao.endRun(it, timeMs) }

		val run = TrackerRun(
			startTimeMs = timeMs,
			endTimeMs = null,
			policy = newPolicy.name,
			policyParams = buildPolicyParams(policy = newPolicy, reason = reason),
			userInitiated = isUserInitiated,
			createdAt = timeMs,
		)
		currentRunId = withContext(dispatchers.io) {
			trackerRunDao.insert(run)
		}
		lastTransitionTime = timeMs

		if (BuildConfig.DEBUG) {
			android.util.Log.d(
				"TrackingPolicy",
				"Transition: $oldPolicy -> $newPolicy (reason: $reason)"
			)
		}
	}

	private suspend fun checkCooldown(currentTimeMs: Long) {
		val timeSinceTransition = currentTimeMs - lastTransitionTime
		if (timeSinceTransition < COOLDOWN_DURATION_MS) return

		when (_currentPolicy.value) {
			TrackingPolicy.ACTIVE_ELEVATED -> {
				transitionTo(TrackingPolicy.ACTIVE_MODERATE, PolicyTransitionReason.MOVEMENT_COOLDOWN, currentTimeMs)
			}
			TrackingPolicy.ACTIVE_MODERATE -> {
				transitionTo(TrackingPolicy.MOVEMENT_SUSPECTED, PolicyTransitionReason.MOVEMENT_COOLDOWN, currentTimeMs)
			}
			TrackingPolicy.MOVEMENT_SUSPECTED -> {
				transitionTo(TrackingPolicy.PASSIVE_LOW, PolicyTransitionReason.MOVEMENT_COOLDOWN, currentTimeMs)
			}
			else -> { /* No cooldown for PASSIVE_LOW or USER_INITIATED */ }
		}
	}

	private fun buildPolicyParams(
		policy: TrackingPolicy,
		reason: PolicyTransitionReason? = null,
	): String {
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
			appendControlledExplorationDecision(policy)
			append("}")
		}
	}

	private fun buildEngineParams(policy: TrackingPolicy, reasonName: String): String {
		val accValue = escalationEngine?.policyState?.value?.accumulatorValue ?: 0.0
		val tier = escalationEngine?.policyState?.value?.tier?.name ?: "UNKNOWN"
		return buildString {
			append("{")
			append("\"engine\":\"PolicyEscalationEngine\",")
			append("\"tier\":\"$tier\",")
			append("\"accumulatorValue\":$accValue,")
			append("\"transitionReason\":\"$reasonName\"")
			appendControlledExplorationDecision(policy)
			append("}")
		}
	}

	private fun StringBuilder.appendControlledExplorationDecision(policy: TrackingPolicy) {
		val decision = ProductionControlledExplorationPolicy.decide(
			ControlledExplorationContext(
				policy = policy,
				isUserInitiated = isUserInitiated,
			)
		)
		append(",\"controlledExploration\":{")
		append("\"eligible\":${decision.eligible},")
		append("\"propensity\":${decision.propensity},")
		append("\"selected\":${decision.selected},")
		append("\"reason\":\"${decision.reason.name}\"")
		append("}")
	}

	companion object {
		private const val STEP_RATE_MOVEMENT_SUSPECTED = 10f
		private const val STEP_RATE_ACTIVE_MODERATE = 40f
		private const val STEP_RATE_ACTIVE_ELEVATED = 80f
		private const val ACTIVITY_CONFIDENCE_THRESHOLD = 50
		private const val DISPLACEMENT_THRESHOLD_METERS = 50f
		private const val COOLDOWN_DURATION_MS = 5 * 60 * 1000L
	}
}
