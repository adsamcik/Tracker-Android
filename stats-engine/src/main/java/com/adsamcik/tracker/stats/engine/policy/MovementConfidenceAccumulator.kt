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

		confidence = (confidence + SIGNIFICANT_MOTION_WEIGHT).coerceIn(0.0, MAX_CONFIDENCE)
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
	}

	/**
	 * Set the current tier externally (e.g., from user override or trip lock).
	 */
	fun setTier(tier: PolicyTier, timestampMs: Long) {
		currentTier = tier
		tierEntryTimeMs = timestampMs
	}

	private fun evaluateTierTransition(timestampMs: Long): PolicyTier? {
		val newTier = tierFromConfidence(confidence)

		if (newTier == currentTier) return null

		// Escalation
		if (newTier > currentTier) {
			if (!canEscalate(timestampMs)) return null
			val previousTier = currentTier
			currentTier = newTier
			tierEntryTimeMs = timestampMs
			// Track potential false escalation
			if (previousTier < PolicyTier.ACTIVE && newTier >= PolicyTier.ACTIVE) {
				trackEscalation(timestampMs)
			}
			return newTier
		}

		// De-escalation — requires longer dwell time (asymmetric)
		if (newTier < currentTier) {
			if (!canDeEscalate(timestampMs)) return null
			currentTier = newTier
			tierEntryTimeMs = timestampMs
			lastDeEscalationTimeMs = timestampMs
			return newTier
		}

		return null
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
		// De-escalation requires longer sustained stillness (asymmetric)
		val minDeEscalationDwell = deEscalationDwellTimeMs(currentTier)
		return timestampMs - tierEntryTimeMs >= minDeEscalationDwell
	}

	private fun trackEscalation(timestampMs: Long) {
		// Reset window if more than 1 hour has passed
		if (timestampMs - falseEscalationWindowStartMs > FALSE_ESCALATION_WINDOW_MS) {
			falseEscalationCount = 0
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
		const val DECAY_RATE = 2.0

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
		private const val MAX_THRESHOLD_MULTIPLIER = 4.0

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
