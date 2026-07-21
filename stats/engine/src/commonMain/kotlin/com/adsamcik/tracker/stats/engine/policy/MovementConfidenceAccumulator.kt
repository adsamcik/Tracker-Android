package com.adsamcik.tracker.stats.engine.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier

/**
 * Weighted confidence accumulator for policy tier escalation.
 *
 * Processes three signal pathways:
 * - Activity recognition (weight 20-30 per event)
 * - Step rate (weight 5-25 based on steps/min)
 * - Significant motion (weight 15 per event)
 *
 * Decays at [DECAY_RATE] points per second of silence.
 * Thresholds: 40→MOVEMENT_SUSPECTED, 80→ACTIVE, 120→ELEVATED/PRECISION.
 *
 * Includes asymmetric hysteresis to prevent oscillation:
 * - Minimum dwell time per tier (30s-120s)
 * - Re-escalation cooldown after de-escalation (60s-300s)
 * - Exponential backoff: 3+ false cycles/hour doubles thresholds
 */
class MovementConfidenceAccumulator(
	private val clock: () -> Long = System::currentTimeMillis,
) {
	private var confidence: Double = 0.0
	private var lastUpdateMs: Long = 0L
	private var lastStepCount: Long = -1L
	private var lastStepTimeMs: Long = 0L
	private var currentTier: PolicyTier = PolicyTier.OFF
	private var tierEntryTimeMs: Long = 0L
	private var lastDeEscalationTimeMs: Long = 0L
	private var falseEscalationCount: Int = 0
	private var falseEscalationWindowStartMs: Long = 0L
	private var thresholdMultiplier: Double = 1.0
	private var trackedEscalationTier: PolicyTier? = null
	private var lastMotionEvidenceMs: Long = 0L
	private var stillnessStartedMs: Long = 0L

	/**
	 * Current confidence value (0 to ~200).
	 */
	val currentConfidence: Double get() = confidence

	/**
	 * Current tier based on confidence thresholds.
	 */
	val tier: PolicyTier get() = currentTier

	/**
	 * Apply time-based decay and return the new confidence value.
	 * Call this before reading [currentConfidence] to get an up-to-date value.
	 *
	 * @param timestampMs Current time in epoch millis
	 * @return Updated confidence value
	 */
	fun decay(timestampMs: Long): Double {
		if (lastUpdateMs > 0L) {
			val elapsedSeconds = (timestampMs - lastUpdateMs).coerceAtLeast(0L) / 1000.0
			confidence = (confidence - DECAY_RATE * elapsedSeconds).coerceAtLeast(0.0)
		}
		lastUpdateMs = timestampMs
		return confidence
	}

	/**
	 * Feed an activity recognition event.
	 *
	 * @param activity Detected activity type
	 * @param apiConfidence Play Services confidence (0-100)
	 * @param timestampMs Event timestamp
	 * @return New tier after processing, or null if no tier change
	 */
	fun onActivityDetected(
		activity: DetectedActivityType,
		apiConfidence: Int,
		timestampMs: Long,
	): PolicyTier? {
		decay(timestampMs)

		observeActivityEvidence(activity, apiConfidence, timestampMs)

		val weight = activityWeight(activity, apiConfidence)
		confidence = (confidence + weight).coerceIn(0.0, MAX_CONFIDENCE)
		lastUpdateMs = timestampMs

		return evaluateTierTransition(timestampMs)
	}

	/**
	 * Feed a step count update.
	 *
	 * @param cumulativeStepCount Cumulative steps since boot
	 * @param timestampMs Event timestamp
	 * @return New tier after processing, or null if no tier change
	 */
	fun onStepCount(cumulativeStepCount: Long, timestampMs: Long): PolicyTier? {
		decay(timestampMs)

		if (lastStepCount >= 0L && lastStepTimeMs > 0L) {
			val deltaSteps = cumulativeStepCount - lastStepCount
			val deltaTimeMin = (timestampMs - lastStepTimeMs).coerceAtLeast(1L) / 60_000.0
			if (deltaTimeMin > 0.0 && deltaSteps > 0) {
				markMotionEvidence(timestampMs)
				val stepsPerMin = deltaSteps / deltaTimeMin
				val weight = stepRateWeight(stepsPerMin)
				confidence = (confidence + weight).coerceIn(0.0, MAX_CONFIDENCE)
			}
		}

		lastStepCount = cumulativeStepCount
		lastStepTimeMs = timestampMs
		lastUpdateMs = timestampMs

		return evaluateTierTransition(timestampMs)
	}

	/**
	 * Feed a significant motion sensor trigger.
	 *
	 * @param timestampMs Event timestamp
	 * @return New tier after processing, or null if no tier change
	 */
	fun onSignificantMotion(timestampMs: Long): PolicyTier? {
		decay(timestampMs)
		markMotionEvidence(timestampMs)

		confidence = (confidence + SIGNIFICANT_MOTION_WEIGHT).coerceIn(0.0, MAX_CONFIDENCE)
		lastUpdateMs = timestampMs

		return evaluateTierTransition(timestampMs)
	}

	/**
	 * Feed a speed observation from an accepted location fix. Sustained non-trivial speed is
	 * positive motion evidence even when step and activity callbacks are sparse (for example,
	 * while travelling in a vehicle).
	 */
	fun onSpeedObserved(speedMps: Float?, timestampMs: Long): PolicyTier? {
		decay(timestampMs)
		if (speedMps != null && speedMps.isFinite() && speedMps >= MIN_MOVING_SPEED_MPS) {
			markMotionEvidence(timestampMs)
			confidence = (confidence + SPEED_MOTION_WEIGHT).coerceIn(0.0, MAX_CONFIDENCE)
		}
		lastUpdateMs = timestampMs
		return evaluateTierTransition(timestampMs)
	}

	/**
	 * Reset to initial state.
	 */
	fun reset() {
		confidence = 0.0
		lastUpdateMs = 0L
		lastStepCount = -1L
		lastStepTimeMs = 0L
		currentTier = PolicyTier.OFF
		tierEntryTimeMs = 0L
		lastDeEscalationTimeMs = 0L
		falseEscalationCount = 0
		falseEscalationWindowStartMs = 0L
		thresholdMultiplier = 1.0
		trackedEscalationTier = null
		lastMotionEvidenceMs = 0L
		stillnessStartedMs = 0L
	}

	/**
	 * Set the current tier externally (e.g., from user override or trip lock).
	 */
	fun setTier(tier: PolicyTier, timestampMs: Long) {
		currentTier = tier
		tierEntryTimeMs = timestampMs
		confidence = maxOf(confidence, confidenceFloorForTier(tier))
		lastUpdateMs = timestampMs
		lastMotionEvidenceMs = if (tier.isGpsEnabled) timestampMs else 0L
		stillnessStartedMs = 0L
		trackedEscalationTier = null
	}

	private fun observeActivityEvidence(
		activity: DetectedActivityType,
		apiConfidence: Int,
		timestampMs: Long,
	) {
		val isMoving = activity == DetectedActivityType.RUNNING ||
			activity == DetectedActivityType.ON_BICYCLE ||
			activity == DetectedActivityType.IN_VEHICLE ||
			activity == DetectedActivityType.WALKING ||
			activity == DetectedActivityType.ON_FOOT
		when {
			isMoving && apiConfidence >= MIN_MOTION_ACTIVITY_CONFIDENCE ->
				markMotionEvidence(timestampMs)
			activity == DetectedActivityType.STILL &&
				apiConfidence >= MIN_STILL_ACTIVITY_CONFIDENCE &&
				stillnessStartedMs == 0L -> stillnessStartedMs = timestampMs
		}
	}

	private fun markMotionEvidence(timestampMs: Long) {
		lastMotionEvidenceMs = timestampMs
		stillnessStartedMs = 0L
	}

	private fun evaluateTierTransition(timestampMs: Long): PolicyTier? {
		resetFalseEscalationWindowIfExpired(timestampMs)
		val requestedTier = tierFromConfidence(confidence)

		if (requestedTier == currentTier) return null

		if (requestedTier > currentTier) {
			if (!canEscalate(timestampMs)) return null
			currentTier = requestedTier
			tierEntryTimeMs = timestampMs
			if (requestedTier >= PolicyTier.ACTIVE) {
				trackedEscalationTier = requestedTier
			}
			return requestedTier
		}

		if (!canDeEscalate(timestampMs)) return null

		// Never jump from GPS directly to OFF because one sparse or stale signal should not end a
		// moving track. Each lower tier must independently satisfy its own dwell/evidence window.
		val nextLowerTier = PolicyTier.entries[currentTier.ordinal - 1]
		val effectiveTier = maxOf(requestedTier, nextLowerTier)
		if (trackedEscalationTier == currentTier) {
			if (isRapidFalseEscalation(currentTier, timestampMs - tierEntryTimeMs)) {
				trackFalseEscalation(timestampMs)
			}
			trackedEscalationTier = null
		}
		currentTier = effectiveTier
		tierEntryTimeMs = timestampMs
		lastDeEscalationTimeMs = timestampMs
		return effectiveTier
	}

	private fun tierFromConfidence(value: Double): PolicyTier {
		val t1 = THRESHOLD_AMBIENT * thresholdMultiplier
		val t2 = THRESHOLD_ACTIVE * thresholdMultiplier
		val t3 = THRESHOLD_PRECISION * thresholdMultiplier

		return when {
			value >= t3 -> PolicyTier.PRECISION
			value >= t2 -> PolicyTier.ACTIVE
			value >= t1 -> PolicyTier.AMBIENT
			else -> PolicyTier.OFF
		}
	}

	private fun canEscalate(timestampMs: Long): Boolean {
		// Minimum dwell time check
		val minDwell = minDwellTimeMs(currentTier)
		if (timestampMs - tierEntryTimeMs < minDwell) return false

		// Re-escalation cooldown after recent de-escalation
		if (lastDeEscalationTimeMs > 0L) {
			val cooldown = reEscalationCooldownMs(currentTier)
			if (timestampMs - lastDeEscalationTimeMs < cooldown) return false
		}

		return true
	}

	private fun canDeEscalate(timestampMs: Long): Boolean {
		val minDwell = deEscalationDwellTimeMs(currentTier)
		if (timestampMs - tierEntryTimeMs < minDwell) return false

		val motionBaseline = maxOf(lastMotionEvidenceMs, tierEntryTimeMs)
		val quietDurationMs = (timestampMs - motionBaseline).coerceAtLeast(0L)
		val confirmedStillness = stillnessStartedMs > 0L &&
			timestampMs - stillnessStartedMs >= STILLNESS_CONFIRMATION_MS
		val prolongedAbsenceOfMotion = quietDurationMs >= NO_MOTION_TIMEOUT_MS

		return confirmedStillness || prolongedAbsenceOfMotion
	}

	private fun isRapidFalseEscalation(tier: PolicyTier, activationDurationMs: Long): Boolean {
		// De-escalation is already blocked for the tier's normal dwell. One additional minimum
		// dwell is a narrow grace period that identifies exits at the earliest allowed opportunity.
		val rapidExitLimitMs = deEscalationDwellTimeMs(tier) + minDwellTimeMs(tier)
		return activationDurationMs.coerceAtLeast(0L) < rapidExitLimitMs
	}

	private fun resetFalseEscalationWindowIfExpired(timestampMs: Long) {
		if (
			falseEscalationCount > 0 &&
			timestampMs - falseEscalationWindowStartMs > FALSE_ESCALATION_WINDOW_MS
		) {
			falseEscalationCount = 0
			falseEscalationWindowStartMs = 0L
			thresholdMultiplier = 1.0
		}
	}

	private fun trackFalseEscalation(timestampMs: Long) {
		resetFalseEscalationWindowIfExpired(timestampMs)
		if (falseEscalationCount == 0) {
			falseEscalationWindowStartMs = timestampMs
		}
		falseEscalationCount++

		// 3+ false cycles/hour → double thresholds
		if (falseEscalationCount >= FALSE_ESCALATION_LIMIT) {
			thresholdMultiplier = (thresholdMultiplier * 2.0).coerceAtMost(MAX_THRESHOLD_MULTIPLIER)
		}
	}

	companion object {
		/** Confidence decay rate: points per second of silence. */
		const val DECAY_RATE = 0.05

		/** Threshold to enter AMBIENT tier. */
		const val THRESHOLD_AMBIENT = 40.0

		/** Threshold to enter ACTIVE tier. */
		const val THRESHOLD_ACTIVE = 80.0

		/** Threshold to enter PRECISION tier. */
		const val THRESHOLD_PRECISION = 120.0

		/** Maximum confidence value. */
		const val MAX_CONFIDENCE = 200.0

		/** Weight for significant motion sensor trigger. */
		const val SIGNIFICANT_MOTION_WEIGHT = 15.0

		internal const val SPEED_MOTION_WEIGHT = 5.0
		internal const val MIN_MOVING_SPEED_MPS = 1.0f
		internal const val STILLNESS_CONFIRMATION_MS = 5 * 60_000L
		internal const val NO_MOTION_TIMEOUT_MS = 15 * 60_000L
		private const val MIN_MOTION_ACTIVITY_CONFIDENCE = 50
		private const val MIN_STILL_ACTIVITY_CONFIDENCE = 70

		/** Minimum dwell times per tier (milliseconds). */
		private const val MIN_DWELL_OFF_MS = 0L
		private const val MIN_DWELL_AMBIENT_MS = 30_000L
		private const val MIN_DWELL_ACTIVE_MS = 60_000L
		private const val MIN_DWELL_PRECISION_MS = 120_000L

		/** De-escalation dwell times — longer than escalation (asymmetric). */
		private const val DE_ESCALATION_DWELL_AMBIENT_MS = 240_000L  // 4 min
		private const val DE_ESCALATION_DWELL_ACTIVE_MS = 360_000L   // 6 min
		private const val DE_ESCALATION_DWELL_PRECISION_MS = 480_000L // 8 min

		/** Re-escalation cooldown after de-escalation. */
		private const val COOLDOWN_OFF_MS = 0L
		private const val COOLDOWN_AMBIENT_MS = 60_000L
		private const val COOLDOWN_ACTIVE_MS = 180_000L
		private const val COOLDOWN_PRECISION_MS = 300_000L

		/** False escalation tracking. */
		private const val FALSE_ESCALATION_WINDOW_MS = 3_600_000L // 1 hour
		private const val FALSE_ESCALATION_LIMIT = 3
		private const val MAX_THRESHOLD_MULTIPLIER = MAX_CONFIDENCE / THRESHOLD_PRECISION

		private fun confidenceFloorForTier(tier: PolicyTier): Double = when (tier) {
			PolicyTier.OFF -> 0.0
			PolicyTier.AMBIENT -> THRESHOLD_AMBIENT
			PolicyTier.ACTIVE -> THRESHOLD_ACTIVE
			PolicyTier.PRECISION -> THRESHOLD_PRECISION
		}

		/**
		 * Calculate weight for an activity recognition event.
		 */
		internal fun activityWeight(activity: DetectedActivityType, apiConfidence: Int): Double {
			val baseWeight = when (activity) {
				DetectedActivityType.RUNNING -> 30.0
				DetectedActivityType.ON_BICYCLE -> 28.0
				DetectedActivityType.IN_VEHICLE -> 25.0
				DetectedActivityType.WALKING -> 20.0
				DetectedActivityType.ON_FOOT -> 20.0
				DetectedActivityType.STILL -> -15.0
				DetectedActivityType.TILTING -> 5.0
				DetectedActivityType.UNKNOWN -> 0.0
			}
			// Scale by API confidence (0-100 → 0.0-1.0)
			val confidenceScale = (apiConfidence.coerceIn(0, 100)) / 100.0
			return baseWeight * confidenceScale
		}

		/**
		 * Calculate weight for step rate.
		 * Higher step rates → higher weight (up to 25 points).
		 */
		internal fun stepRateWeight(stepsPerMinute: Double): Double = when {
			stepsPerMinute >= 120 -> 25.0  // Running
			stepsPerMinute >= 80 -> 15.0   // Brisk walking
			stepsPerMinute >= 40 -> 10.0   // Normal walking
			stepsPerMinute >= 10 -> 5.0    // Slow movement
			else -> 0.0
		}

		private fun minDwellTimeMs(tier: PolicyTier): Long = when (tier) {
			PolicyTier.OFF -> MIN_DWELL_OFF_MS
			PolicyTier.AMBIENT -> MIN_DWELL_AMBIENT_MS
			PolicyTier.ACTIVE -> MIN_DWELL_ACTIVE_MS
			PolicyTier.PRECISION -> MIN_DWELL_PRECISION_MS
		}

		private fun deEscalationDwellTimeMs(tier: PolicyTier): Long = when (tier) {
			PolicyTier.OFF -> 0L
			PolicyTier.AMBIENT -> DE_ESCALATION_DWELL_AMBIENT_MS
			PolicyTier.ACTIVE -> DE_ESCALATION_DWELL_ACTIVE_MS
			PolicyTier.PRECISION -> DE_ESCALATION_DWELL_PRECISION_MS
		}

		private fun reEscalationCooldownMs(tier: PolicyTier): Long = when (tier) {
			PolicyTier.OFF -> COOLDOWN_OFF_MS
			PolicyTier.AMBIENT -> COOLDOWN_AMBIENT_MS
			PolicyTier.ACTIVE -> COOLDOWN_ACTIVE_MS
			PolicyTier.PRECISION -> COOLDOWN_PRECISION_MS
		}
	}
}
