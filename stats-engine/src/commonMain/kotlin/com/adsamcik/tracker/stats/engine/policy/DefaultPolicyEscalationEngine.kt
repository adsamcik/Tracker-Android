package com.adsamcik.tracker.stats.engine.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyEscalationEngine
import com.adsamcik.tracker.stats.api.PolicyState
import com.adsamcik.tracker.stats.api.PolicyState.TransitionReason
import com.adsamcik.tracker.stats.api.PolicyTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default implementation of [PolicyEscalationEngine].
 *
 * Wraps [MovementConfidenceAccumulator] for tier transition decisions
 * and [ActivityAwareIntervalMapper] for GPS interval calculation within
 * the ACTIVE tier.
 *
 * Thread safety: All public methods synchronize on [lock]. The engine
 * is designed to be called from a single coroutine scope (TrackerService),
 * but the lock protects against concurrent signal delivery.
 *
 * @param clock Time source for testability (epoch millis)
 */
class DefaultPolicyEscalationEngine(
	private val clock: () -> Long = System::currentTimeMillis,
) : PolicyEscalationEngine {

	private val lock = Any()

	private val accumulator = MovementConfidenceAccumulator(clock)

	private val _policyState = MutableStateFlow(
		PolicyState(
			tier = PolicyTier.OFF,
			transitionReason = TransitionReason.INITIALIZATION,
		)
	)

	override val policyState: StateFlow<PolicyState> = _policyState.asStateFlow()

	private var minimumTier: PolicyTier? = null
	private var minimumTierReason: String? = null
	private var lastActivity: DetectedActivityType = DetectedActivityType.UNKNOWN
	private var lastSpeedMps: Float? = null
	private var isRunning: Boolean = false

	override fun onActivityDetected(
		activity: DetectedActivityType,
		confidence: Int,
		timestampMs: Long,
	) = synchronized(lock) {
		if (!isRunning) return
		lastActivity = activity
		val tierChange = accumulator.onActivityDetected(activity, confidence, timestampMs)
		applyTierChange(tierChange, TransitionReason.ACCUMULATOR_ESCALATION, timestampMs)
	}

	override fun onStepCount(stepCount: Long, timestampMs: Long) = synchronized(lock) {
		if (!isRunning) return
		val tierChange = accumulator.onStepCount(stepCount, timestampMs)
		applyTierChange(tierChange, TransitionReason.ACCUMULATOR_ESCALATION, timestampMs)
	}

	override fun onSignificantMotion(timestampMs: Long) = synchronized(lock) {
		if (!isRunning) return
		val tierChange = accumulator.onSignificantMotion(timestampMs)
		applyTierChange(tierChange, TransitionReason.ACCUMULATOR_ESCALATION, timestampMs)
	}

	override fun setMinimumTier(tier: PolicyTier, reason: String) = synchronized(lock) {
		minimumTier = tier
		minimumTierReason = reason
		val current = _policyState.value
		if (current.tier < tier) {
			emitState(
				tier = tier,
				reason = TransitionReason.TRIP_LOCK,
				timestampMs = clock(),
			)
		} else {
			// Just update the lock field without changing tier
			_policyState.value = current.copy(minimumTierLock = tier)
		}
	}

	override fun clearMinimumTier() = synchronized(lock) {
		minimumTier = null
		minimumTierReason = null
		val now = clock()
		// Re-evaluate: tier may need to drop if confidence is low
		val naturalTier = accumulator.tier
		val current = _policyState.value
		if (naturalTier < current.tier) {
			emitState(
				tier = naturalTier,
				reason = TransitionReason.TRIP_UNLOCK,
				timestampMs = now,
			)
		} else {
			_policyState.value = current.copy(minimumTierLock = null)
		}
	}

	override fun overrideTier(tier: PolicyTier, reason: String) = synchronized(lock) {
		val now = clock()
		accumulator.setTier(tier, now)
		emitState(
			tier = tier,
			reason = TransitionReason.USER_INITIATED,
			timestampMs = now,
		)
	}

	override fun start(initialTier: PolicyTier, timestampMs: Long) = synchronized(lock) {
		isRunning = true
		accumulator.reset()
		accumulator.setTier(initialTier, timestampMs)
		lastActivity = DetectedActivityType.UNKNOWN
		lastSpeedMps = null
		minimumTier = null
		minimumTierReason = null
		emitState(
			tier = initialTier,
			reason = TransitionReason.INITIALIZATION,
			timestampMs = timestampMs,
		)
	}

	override fun stop() = synchronized(lock) {
		isRunning = false
	}

	/**
	 * Update the last known speed. Called by the tracker service when a
	 * location fix includes speed information.
	 */
	fun updateSpeed(speedMps: Float?) {
		lastSpeedMps = speedMps
	}

	private fun applyTierChange(
		newTier: PolicyTier?,
		reason: TransitionReason,
		timestampMs: Long,
	) {
		if (newTier == null) {
			// No tier change, but still update accumulator value in state
			val current = _policyState.value
			val newAccValue = accumulator.currentConfidence
			if (current.accumulatorValue != newAccValue || current.detectedActivity != lastActivity) {
				_policyState.value = current.copy(
					accumulatorValue = newAccValue,
					detectedActivity = lastActivity,
					gpsIntervalMs = computeGpsInterval(current.tier),
				)
			}
			return
		}

		// Apply minimum tier lock
		val effectiveTier = when {
			minimumTier != null && newTier < minimumTier!! -> minimumTier!!
			else -> newTier
		}

		val deEscalating = effectiveTier < _policyState.value.tier
		val effectiveReason = when {
			deEscalating -> TransitionReason.STILLNESS_DE_ESCALATION
			else -> reason
		}

		emitState(
			tier = effectiveTier,
			reason = effectiveReason,
			timestampMs = timestampMs,
		)
	}

	private fun emitState(
		tier: PolicyTier,
		reason: TransitionReason,
		timestampMs: Long,
	) {
		_policyState.value = PolicyState(
			tier = tier,
			transitionReason = reason,
			accumulatorValue = accumulator.currentConfidence,
			detectedActivity = lastActivity,
			gpsIntervalMs = computeGpsInterval(tier),
			minimumTierLock = minimumTier,
			tierEntryTimeMs = timestampMs,
		)
	}

	private fun computeGpsInterval(tier: PolicyTier): Long? {
		if (!tier.isGpsEnabled) return null
		return ActivityAwareIntervalMapper.getInterval(tier, lastActivity, lastSpeedMps)
	}
}
