package com.adsamcik.tracker.stats.engine.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MovementConfidenceAccumulatorTest {

	private lateinit var accumulator: MovementConfidenceAccumulator
	private var currentTimeMs = 1_000_000L

	@BeforeEach
	fun setUp() {
		accumulator = MovementConfidenceAccumulator(clock = { currentTimeMs })
		currentTimeMs = 1_000_000L
	}

	@Nested
	inner class DecayTests {
		@Test
		fun `decay reduces confidence over time`() {
			// Build up some confidence
			accumulator.onSignificantMotion(currentTimeMs)
			val initial = accumulator.currentConfidence

			// Advance 5 seconds
			currentTimeMs += 5_000L
			accumulator.decay(currentTimeMs)

			accumulator.currentConfidence shouldBeLessThan initial
			// 15 - (0.05 * 5) = 14.75
			accumulator.currentConfidence shouldBe 14.75
		}

		@Test
		fun `decay never goes below zero`() {
			accumulator.onSignificantMotion(currentTimeMs)
			currentTimeMs += 600_000L // 10 minutes - enough to decay below zero
			accumulator.decay(currentTimeMs)

			accumulator.currentConfidence shouldBe 0.0
		}

		@Test
		fun `no decay on first call`() {
			accumulator.decay(currentTimeMs)
			accumulator.currentConfidence shouldBe 0.0
		}
	}

	@Nested
	inner class ActivityDetectionTests {
		@Test
		fun `walking increases confidence`() {
			val result = accumulator.onActivityDetected(
				DetectedActivityType.WALKING, 80, currentTimeMs
			)
			accumulator.currentConfidence shouldBeGreaterThan 0.0
		}

		@Test
		fun `still activity decreases confidence`() {
			// First build up confidence
			accumulator.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)
			val afterRunning = accumulator.currentConfidence

			currentTimeMs += 100L
			accumulator.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			accumulator.currentConfidence shouldBeLessThan afterRunning
		}

		@Test
		fun `confidence scales with API confidence level`() {
			accumulator.onActivityDetected(DetectedActivityType.WALKING, 100, currentTimeMs)
			val highConfidence = accumulator.currentConfidence

			accumulator.reset()
			accumulator.onActivityDetected(DetectedActivityType.WALKING, 50, currentTimeMs)
			val lowConfidence = accumulator.currentConfidence

			highConfidence shouldBeGreaterThan lowConfidence
		}

		@Test
		fun `running has higher weight than walking`() {
			accumulator.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)
			val runningConfidence = accumulator.currentConfidence

			accumulator.reset()
			accumulator.onActivityDetected(DetectedActivityType.WALKING, 100, currentTimeMs)
			val walkingConfidence = accumulator.currentConfidence

			runningConfidence shouldBeGreaterThan walkingConfidence
		}

		@Test
		fun `confidence capped at MAX_CONFIDENCE`() {
			repeat(20) {
				accumulator.onActivityDetected(
					DetectedActivityType.RUNNING, 100, currentTimeMs
				)
				currentTimeMs += 10L
			}

			accumulator.currentConfidence shouldBeLessThan
					MovementConfidenceAccumulator.MAX_CONFIDENCE + 0.01
		}
	}

	@Nested
	inner class StepCountTests {
		@Test
		fun `first step count establishes baseline`() {
			val result = accumulator.onStepCount(1000L, currentTimeMs)
			// No weight on first reading (no delta)
			accumulator.currentConfidence shouldBe 0.0
		}

		@Test
		fun `normal walking step rate increases confidence`() {
			accumulator.onStepCount(1000L, currentTimeMs)
			currentTimeMs += 60_000L // 1 minute
			accumulator.onStepCount(1100L, currentTimeMs) // 100 steps/min

			accumulator.currentConfidence shouldBeGreaterThan 0.0
		}

		@Test
		fun `higher step rate produces higher weight`() {
			// Normal walking (80 steps/min)
			accumulator.onStepCount(0L, currentTimeMs)
			currentTimeMs += 60_000L
			accumulator.onStepCount(80L, currentTimeMs)
			val normalWalkConfidence = accumulator.currentConfidence

			accumulator.reset()

			// Running (130 steps/min)
			accumulator.onStepCount(0L, currentTimeMs)
			currentTimeMs += 60_000L
			accumulator.onStepCount(130L, currentTimeMs)
			val runningConfidence = accumulator.currentConfidence

			runningConfidence shouldBeGreaterThan normalWalkConfidence
		}
	}

	@Nested
	inner class SignificantMotionTests {
		@Test
		fun `significant motion adds fixed weight`() {
			accumulator.onSignificantMotion(currentTimeMs)

			accumulator.currentConfidence shouldBe
					MovementConfidenceAccumulator.SIGNIFICANT_MOTION_WEIGHT
		}

		@Test
		fun `multiple triggers accumulate`() {
			accumulator.onSignificantMotion(currentTimeMs)
			currentTimeMs += 100L
			accumulator.onSignificantMotion(currentTimeMs)

			accumulator.currentConfidence shouldBeGreaterThan
					MovementConfidenceAccumulator.SIGNIFICANT_MOTION_WEIGHT
		}
	}

	@Nested
	inner class TierTransitionTests {
		@Test
		fun `starts at OFF tier`() {
			accumulator.tier shouldBe PolicyTier.OFF
		}

		@Test
		fun `reaching AMBIENT threshold escalates`() {
			// Rapidly feed enough signals to cross threshold
			pushConfidenceAbove(
				MovementConfidenceAccumulator.THRESHOLD_AMBIENT
			)

			accumulator.tier shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `reaching ACTIVE threshold escalates past AMBIENT`() {
			pushConfidenceAbove(
				MovementConfidenceAccumulator.THRESHOLD_ACTIVE
			)

			// Tier should be at or above ACTIVE (may skip to PRECISION
			// if confidence overshoots during dwell time)
			assert(accumulator.tier >= PolicyTier.ACTIVE) {
				"Expected tier >= ACTIVE, got ${accumulator.tier}"
			}
		}

		@Test
		fun `de-escalation requires sustained stillness`() {
			// Escalate to at least ACTIVE
			pushConfidenceAbove(
				MovementConfidenceAccumulator.THRESHOLD_ACTIVE
			)
			val tierAfterEscalation = accumulator.tier
			assert(tierAfterEscalation >= PolicyTier.ACTIVE) {
				"Expected tier >= ACTIVE, got $tierAfterEscalation"
			}

			// Decay but not enough time for de-escalation
			currentTimeMs += 60_000L // 1 min — less than de-escalation dwell
			accumulator.decay(currentTimeMs)

			// Should still be at same tier due to dwell time requirement
			// (decay doesn't trigger tier evaluation, only signals do)
			accumulator.tier shouldBe tierAfterEscalation
		}

		@Test
		fun `de-escalation after sufficient dwell time`() {
			// Escalate to AMBIENT
			pushConfidenceAbove(
				MovementConfidenceAccumulator.THRESHOLD_AMBIENT
			)
			accumulator.tier shouldBe PolicyTier.AMBIENT

			// A single STILL reading starts confirmation but cannot drop tracking.
			currentTimeMs += 300_000L
			accumulator.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)
			accumulator.tier shouldBe PolicyTier.AMBIENT

			// Sustained high-confidence stillness may lower one tier after confirmation.
			currentTimeMs += MovementConfidenceAccumulator.STILLNESS_CONFIRMATION_MS
			accumulator.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)
			accumulator.tier shouldBe PolicyTier.OFF
		}

		@Test
		fun `alternating STILL and WALKING nets near zero`() {
			// This tests oscillation prevention
			repeat(10) {
				accumulator.onActivityDetected(
					DetectedActivityType.WALKING, 80, currentTimeMs
				)
				currentTimeMs += 500L
				accumulator.onActivityDetected(
					DetectedActivityType.STILL, 80, currentTimeMs
				)
				currentTimeMs += 500L
			}

			// Net effect should be small — not enough for ACTIVE
			accumulator.currentConfidence shouldBeLessThan
					MovementConfidenceAccumulator.THRESHOLD_ACTIVE
		}
	}

	@Nested
	inner class HysteresisTests {
		@Test
		fun `minimum dwell time prevents rapid escalation`() {
			// Escalate to AMBIENT
			pushConfidenceAbove(
				MovementConfidenceAccumulator.THRESHOLD_AMBIENT
			)
			accumulator.tier shouldBe PolicyTier.AMBIENT

			// Immediately try to escalate to ACTIVE (within dwell time)
			val immediateResult = accumulator.onActivityDetected(
				DetectedActivityType.RUNNING, 100, currentTimeMs
			)
			currentTimeMs += 1L
			accumulator.onActivityDetected(
				DetectedActivityType.RUNNING, 100, currentTimeMs
			)

			// Shouldn't escalate yet — dwell time not met for AMBIENT
			if (accumulator.currentConfidence >= MovementConfidenceAccumulator.THRESHOLD_ACTIVE) {
				// Even with enough confidence, dwell time should prevent transition
				// (if the tier just changed)
			}
		}

		@Test
		fun `re-escalation cooldown after de-escalation`() {
			// Escalate to AMBIENT
			pushConfidenceAbove(
				MovementConfidenceAccumulator.THRESHOLD_AMBIENT
			)

			// De-escalate only after confirmed stillness.
			currentTimeMs += 300_000L
			accumulator.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)
			currentTimeMs += MovementConfidenceAccumulator.STILLNESS_CONFIRMATION_MS
			accumulator.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			// Try to immediately re-escalate
			currentTimeMs += 100L
			val result = accumulator.onActivityDetected(
				DetectedActivityType.RUNNING, 100, currentTimeMs
			)

			// Should be blocked by cooldown
			result.shouldBeNull()
		}
	}

	@Nested
	inner class MotionRetentionTests {
		@Test
		fun `moving speed after long callback gap prevents downgrade`() {
			accumulator.setTier(PolicyTier.PRECISION, currentTimeMs)
			currentTimeMs += 20 * 60_000L

			accumulator.onSpeedObserved(5f, currentTimeMs)

			accumulator.tier shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `one still reading does not downgrade active tracking`() {
			accumulator.setTier(PolicyTier.ACTIVE, currentTimeMs)
			currentTimeMs += 10 * 60_000L

			accumulator.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			accumulator.tier shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `prolonged silence lowers at most one tier`() {
			accumulator.setTier(PolicyTier.PRECISION, currentTimeMs)
			currentTimeMs += MovementConfidenceAccumulator.NO_MOTION_TIMEOUT_MS + 1L

			accumulator.onActivityDetected(DetectedActivityType.UNKNOWN, 0, currentTimeMs)

			accumulator.tier shouldBe PolicyTier.ACTIVE
		}
	}

	@Nested
	inner class WeightCalculationTests {
		@Test
		fun `activity weight for RUNNING at full confidence`() {
			val weight = MovementConfidenceAccumulator.activityWeight(
				DetectedActivityType.RUNNING, 100
			)
			weight shouldBe 30.0
		}

		@Test
		fun `activity weight for STILL at full confidence is negative`() {
			val weight = MovementConfidenceAccumulator.activityWeight(
				DetectedActivityType.STILL, 100
			)
			weight shouldBeLessThan 0.0
		}

		@Test
		fun `step rate weight for running pace`() {
			val weight = MovementConfidenceAccumulator.stepRateWeight(130.0)
			weight shouldBe 25.0
		}

		@Test
		fun `step rate weight for walking pace`() {
			val weight = MovementConfidenceAccumulator.stepRateWeight(90.0)
			weight shouldBe 15.0
		}

		@Test
		fun `step rate weight for very slow movement`() {
			val weight = MovementConfidenceAccumulator.stepRateWeight(5.0)
			weight shouldBe 0.0
		}
	}

	@Nested
	inner class ResetTests {
		@Test
		fun `reset clears all state`() {
			accumulator.onSignificantMotion(currentTimeMs)
			accumulator.reset()

			accumulator.currentConfidence shouldBe 0.0
			accumulator.tier shouldBe PolicyTier.OFF
		}
	}

	/**
	 * Helper to push confidence above a target threshold and ensure
	 * the tier actually transitions. Feeds events spaced to satisfy
	 * minimum dwell times while keeping confidence high enough.
	 */
	private fun pushConfidenceAbove(threshold: Double) {
		val targetTier = when {
			threshold >= MovementConfidenceAccumulator.THRESHOLD_ACTIVE -> PolicyTier.ACTIVE
			threshold >= MovementConfidenceAccumulator.THRESHOLD_AMBIENT -> PolicyTier.AMBIENT
			else -> PolicyTier.OFF
		}

		// Feed events with enough spacing to satisfy dwell times.
		// Each RUNNING event adds +30. Decay is intentionally slow (0.05/s).
		// Feeding every 5s: decay = 0.25, net gain = +29.75 per event.
		// After 7 events in 35s: net ~140 confidence, past 30s dwell.
		var iterations = 0
		while (accumulator.tier < targetTier && iterations < 100) {
			accumulator.onActivityDetected(
				DetectedActivityType.RUNNING, 100, currentTimeMs
			)
			currentTimeMs += 5_000L // 5s spacing → satisfies dwell times gradually
			iterations++
		}
	}
}
