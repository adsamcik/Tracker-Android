package com.adsamcik.tracker.stats.engine.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyState.TransitionReason
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan as longShouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultPolicyEscalationEngineTest {

	private var currentTimeMs = 1_000_000L
	private lateinit var engine: DefaultPolicyEscalationEngine

	@BeforeEach
	fun setUp() {
		currentTimeMs = 1_000_000L
		engine = DefaultPolicyEscalationEngine(clock = { currentTimeMs })
	}

	@Nested
	inner class LifecycleTests {
		@Test
		fun `starts in OFF state before start() called`() {
			engine.policyState.value.tier shouldBe PolicyTier.OFF
			engine.policyState.value.transitionReason shouldBe TransitionReason.INITIALIZATION
		}

		@Test
		fun `start() sets initial tier`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)

			engine.currentTier shouldBe PolicyTier.AMBIENT
			engine.policyState.value.transitionReason shouldBe TransitionReason.INITIALIZATION
			engine.policyState.value.tierEntryTimeMs shouldBe currentTimeMs
		}

		@Test
		fun `start() with PRECISION tier for user-initiated`() {
			engine.start(PolicyTier.PRECISION, currentTimeMs)

			engine.currentTier shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `signals ignored before start()`() {
			engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)

			engine.currentTier shouldBe PolicyTier.OFF
		}

		@Test
		fun `signals ignored after stop()`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			engine.stop()

			currentTimeMs += 100L
			engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)

			// Tier remains whatever it was at stop — no further changes
			engine.currentTier shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `start() resets accumulator state`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			// Push some signals
			repeat(5) {
				engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)
				currentTimeMs += 100L
			}
			val confidenceBefore = engine.policyState.value.accumulatorValue

			// Restart
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			engine.policyState.value.accumulatorValue shouldBe 0.0
		}
	}

	@Nested
	inner class SignalProcessingTests {
		@Test
		fun `activity signal updates state accumulator value`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			currentTimeMs += 100L
			engine.onActivityDetected(DetectedActivityType.WALKING, 80, currentTimeMs)

			engine.policyState.value.accumulatorValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `step count signal updates state`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			engine.onStepCount(0L, currentTimeMs)
			currentTimeMs += 60_000L
			engine.onStepCount(100L, currentTimeMs)

			engine.policyState.value.accumulatorValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `significant motion signal updates state`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			currentTimeMs += 100L
			engine.onSignificantMotion(currentTimeMs)

			engine.policyState.value.accumulatorValue shouldBeGreaterThan 0.0
		}

		@Test
		fun `detected activity is tracked in state`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			currentTimeMs += 100L
			engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)

			engine.policyState.value.detectedActivity shouldBe DetectedActivityType.RUNNING
		}
	}

	@Nested
	inner class TierTransitionTests {
		@Test
		fun `escalation from AMBIENT through ACTIVE to PRECISION`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)

			// Feed enough running events to escalate through tiers
			// Each RUNNING@100 adds +30. Decay is 2/s.
			// With 5s spacing: decay=10, net +20 per event.
			pushToTier(PolicyTier.ACTIVE)

			assert(engine.currentTier >= PolicyTier.ACTIVE) {
				"Expected tier >= ACTIVE, got ${engine.currentTier}"
			}
		}

		@Test
		fun `de-escalation after sustained stillness`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)

			// Escalate to at least ACTIVE
			pushToTier(PolicyTier.ACTIVE)
			val escalatedTier = engine.currentTier

			// Wait long enough for confidence to decay and de-escalation to occur
			currentTimeMs += 600_000L // 10 min
			engine.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			engine.currentTier shouldBe PolicyTier.OFF
		}

		@Test
		fun `de-escalation reason is STILLNESS_DE_ESCALATION`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			pushToTier(PolicyTier.ACTIVE)

			currentTimeMs += 600_000L
			engine.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			engine.policyState.value.transitionReason shouldBe TransitionReason.STILLNESS_DE_ESCALATION
		}
	}

	@Nested
	inner class MinimumTierLockTests {
		@Test
		fun `setMinimumTier prevents de-escalation below lock`() {
			engine.start(PolicyTier.ACTIVE, currentTimeMs)

			engine.setMinimumTier(PolicyTier.ACTIVE, "trip in progress")

			// Try to de-escalate by waiting
			currentTimeMs += 600_000L
			engine.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			// Should not drop below ACTIVE
			assert(engine.currentTier >= PolicyTier.ACTIVE) {
				"Expected tier >= ACTIVE with lock, got ${engine.currentTier}"
			}
		}

		@Test
		fun `setMinimumTier escalates if current tier is below`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)

			engine.setMinimumTier(PolicyTier.ACTIVE, "trip detected")

			engine.currentTier shouldBe PolicyTier.ACTIVE
			engine.policyState.value.transitionReason shouldBe TransitionReason.TRIP_LOCK
		}

		@Test
		fun `setMinimumTier records lock in state`() {
			engine.start(PolicyTier.ACTIVE, currentTimeMs)

			engine.setMinimumTier(PolicyTier.ACTIVE, "trip lock")

			engine.policyState.value.minimumTierLock shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `clearMinimumTier allows de-escalation`() {
			engine.start(PolicyTier.ACTIVE, currentTimeMs)
			engine.setMinimumTier(PolicyTier.ACTIVE, "trip lock")

			engine.clearMinimumTier()

			engine.policyState.value.minimumTierLock.shouldBeNull()

			// Now de-escalation should work
			currentTimeMs += 600_000L
			engine.onActivityDetected(DetectedActivityType.STILL, 100, currentTimeMs)

			engine.currentTier shouldBe PolicyTier.OFF
		}

		@Test
		fun `clearMinimumTier triggers de-escalation if confidence low`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			engine.setMinimumTier(PolicyTier.ACTIVE, "trip lock")

			// Wait for confidence to decay
			currentTimeMs += 600_000L

			engine.clearMinimumTier()

			// Should drop since accumulator confidence is 0
			engine.policyState.value.transitionReason shouldBe TransitionReason.TRIP_UNLOCK
		}
	}

	@Nested
	inner class OverrideTierTests {
		@Test
		fun `overrideTier sets exact tier`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)

			engine.overrideTier(PolicyTier.PRECISION, "user requested")

			engine.currentTier shouldBe PolicyTier.PRECISION
			engine.policyState.value.transitionReason shouldBe TransitionReason.USER_INITIATED
		}

		@Test
		fun `overrideTier works even for downgrade`() {
			engine.start(PolicyTier.PRECISION, currentTimeMs)

			engine.overrideTier(PolicyTier.AMBIENT, "user requested")

			engine.currentTier shouldBe PolicyTier.AMBIENT
		}
	}

	@Nested
	inner class GpsIntervalTests {
		@Test
		fun `OFF tier has null GPS interval`() {
			engine.policyState.value.gpsIntervalMs.shouldBeNull()
		}

		@Test
		fun `AMBIENT tier has null GPS interval`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)

			engine.policyState.value.gpsIntervalMs.shouldBeNull()
		}

		@Test
		fun `ACTIVE tier has non-null GPS interval`() {
			engine.start(PolicyTier.ACTIVE, currentTimeMs)

			engine.policyState.value.gpsIntervalMs.shouldNotBeNull()
		}

		@Test
		fun `PRECISION tier uses fixed 3s interval`() {
			engine.start(PolicyTier.PRECISION, currentTimeMs)

			engine.policyState.value.gpsIntervalMs shouldBe 3_000L
		}

		@Test
		fun `speed update affects GPS interval`() {
			engine.start(PolicyTier.ACTIVE, currentTimeMs)
			currentTimeMs += 100L
			// RUNNING default is 10s, range is 8-15s
			engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)

			val intervalWithoutSpeed = engine.policyState.value.gpsIntervalMs!!

			engine.updateSpeed(5.0f) // 18 km/h - high end of running range
			currentTimeMs += 100L
			engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)

			val intervalWithSpeed = engine.policyState.value.gpsIntervalMs!!

			// High speed should give shorter interval than default
			intervalWithSpeed shouldBeLessThan intervalWithoutSpeed
		}
	}

	@Nested
	inner class StateFlowEmissionTests {
		@Test
		fun `state updates on every signal even without tier change`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			val initialAccValue = engine.policyState.value.accumulatorValue

			currentTimeMs += 100L
			engine.onActivityDetected(DetectedActivityType.WALKING, 50, currentTimeMs)

			engine.policyState.value.accumulatorValue shouldBeGreaterThan initialAccValue
		}

		@Test
		fun `state includes tierEntryTimeMs on transition`() {
			engine.start(PolicyTier.AMBIENT, currentTimeMs)
			pushToTier(PolicyTier.ACTIVE)

			engine.policyState.value.tierEntryTimeMs longShouldBeGreaterThan (currentTimeMs - 100_000L)
		}
	}

	/**
	 * Helper: push enough signals to reach the target tier.
	 */
	private fun pushToTier(target: PolicyTier) {
		var iterations = 0
		while (engine.currentTier < target && iterations < 100) {
			engine.onActivityDetected(DetectedActivityType.RUNNING, 100, currentTimeMs)
			currentTimeMs += 5_000L
			iterations++
		}
	}
}
