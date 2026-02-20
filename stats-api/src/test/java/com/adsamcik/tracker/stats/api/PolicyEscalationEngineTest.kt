package com.adsamcik.tracker.stats.api

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PolicyEscalationEngineTest {

	@Nested
	inner class PolicyTierOrdering {

		@Test
		fun `tiers are ordered OFF - AMBIENT - ACTIVE - PRECISION`() {
			val tiers = PolicyTier.entries
			tiers shouldBe listOf(
				PolicyTier.OFF,
				PolicyTier.AMBIENT,
				PolicyTier.ACTIVE,
				PolicyTier.PRECISION,
			)
		}

		@Test
		fun `OFF is less than AMBIENT`() {
			(PolicyTier.OFF < PolicyTier.AMBIENT).shouldBeTrue()
		}

		@Test
		fun `AMBIENT is less than ACTIVE`() {
			(PolicyTier.AMBIENT < PolicyTier.ACTIVE).shouldBeTrue()
		}

		@Test
		fun `ACTIVE is less than PRECISION`() {
			(PolicyTier.ACTIVE < PolicyTier.PRECISION).shouldBeTrue()
		}
	}

	@Nested
	inner class GpsEnabled {

		@Test
		fun `OFF does not enable GPS`() {
			PolicyTier.OFF.isGpsEnabled.shouldBeFalse()
		}

		@Test
		fun `AMBIENT does not enable GPS`() {
			PolicyTier.AMBIENT.isGpsEnabled.shouldBeFalse()
		}

		@Test
		fun `ACTIVE enables GPS`() {
			PolicyTier.ACTIVE.isGpsEnabled.shouldBeTrue()
		}

		@Test
		fun `PRECISION enables GPS`() {
			PolicyTier.PRECISION.isGpsEnabled.shouldBeTrue()
		}
	}

	@Nested
	inner class SensorEnabled {

		@Test
		fun `OFF does not enable sensors`() {
			PolicyTier.OFF.isSensorEnabled.shouldBeFalse()
		}

		@Test
		fun `AMBIENT enables sensors`() {
			PolicyTier.AMBIENT.isSensorEnabled.shouldBeTrue()
		}

		@Test
		fun `ACTIVE enables sensors`() {
			PolicyTier.ACTIVE.isSensorEnabled.shouldBeTrue()
		}

		@Test
		fun `PRECISION enables sensors`() {
			PolicyTier.PRECISION.isSensorEnabled.shouldBeTrue()
		}
	}

	@Nested
	inner class PolicyStateConstruction {

		@Test
		fun `default PolicyState has zero accumulator`() {
			val state = PolicyState(
				tier = PolicyTier.OFF,
				transitionReason = PolicyState.TransitionReason.INITIALIZATION,
			)
			state.accumulatorValue shouldBe 0.0
		}

		@Test
		fun `default PolicyState has UNKNOWN detected activity`() {
			val state = PolicyState(
				tier = PolicyTier.AMBIENT,
				transitionReason = PolicyState.TransitionReason.INITIALIZATION,
			)
			state.detectedActivity shouldBe DetectedActivityType.UNKNOWN
		}

		@Test
		fun `default PolicyState has null GPS interval`() {
			val state = PolicyState(
				tier = PolicyTier.OFF,
				transitionReason = PolicyState.TransitionReason.INITIALIZATION,
			)
			state.gpsIntervalMs shouldBe null
		}

		@Test
		fun `default PolicyState has null minimum tier lock`() {
			val state = PolicyState(
				tier = PolicyTier.OFF,
				transitionReason = PolicyState.TransitionReason.INITIALIZATION,
			)
			state.minimumTierLock shouldBe null
		}

		@Test
		fun `default PolicyState has zero tier entry time`() {
			val state = PolicyState(
				tier = PolicyTier.OFF,
				transitionReason = PolicyState.TransitionReason.INITIALIZATION,
			)
			state.tierEntryTimeMs shouldBe 0L
		}

		@Test
		fun `PolicyState preserves all custom values`() {
			val state = PolicyState(
				tier = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.ACCUMULATOR_ESCALATION,
				accumulatorValue = 150.0,
				detectedActivity = DetectedActivityType.WALKING,
				gpsIntervalMs = 10_000L,
				minimumTierLock = PolicyTier.AMBIENT,
				tierEntryTimeMs = 1_700_000_000_000L,
			)
			state.tier shouldBe PolicyTier.ACTIVE
			state.transitionReason shouldBe PolicyState.TransitionReason.ACCUMULATOR_ESCALATION
			state.accumulatorValue shouldBe 150.0
			state.detectedActivity shouldBe DetectedActivityType.WALKING
			state.gpsIntervalMs shouldBe 10_000L
			state.minimumTierLock shouldBe PolicyTier.AMBIENT
			state.tierEntryTimeMs shouldBe 1_700_000_000_000L
		}
	}

	@Nested
	inner class TransitionReasonValues {

		@Test
		fun `all seven transition reasons exist`() {
			val reasons = PolicyState.TransitionReason.entries
			reasons.size shouldBe 7
		}

		@Test
		fun `transition reasons are ordered correctly`() {
			PolicyState.TransitionReason.entries shouldBe listOf(
				PolicyState.TransitionReason.INITIALIZATION,
				PolicyState.TransitionReason.ACCUMULATOR_ESCALATION,
				PolicyState.TransitionReason.STILLNESS_DE_ESCALATION,
				PolicyState.TransitionReason.USER_INITIATED,
				PolicyState.TransitionReason.TRIP_LOCK,
				PolicyState.TransitionReason.TRIP_UNLOCK,
				PolicyState.TransitionReason.SYSTEM_CONSTRAINT,
			)
		}
	}

	@Nested
	inner class PolicyStateTierShortcut {

		@Test
		fun `tier property matches constructed tier`() {
			val state = PolicyState(
				tier = PolicyTier.PRECISION,
				transitionReason = PolicyState.TransitionReason.USER_INITIATED,
			)
			state.tier shouldBe PolicyTier.PRECISION
		}
	}

	@Nested
	inner class PolicyStateCopy {

		@Test
		fun `copy with new tier preserves other fields`() {
			val original = PolicyState(
				tier = PolicyTier.AMBIENT,
				transitionReason = PolicyState.TransitionReason.INITIALIZATION,
				accumulatorValue = 50.0,
				detectedActivity = DetectedActivityType.WALKING,
			)
			val escalated = original.copy(
				tier = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.ACCUMULATOR_ESCALATION,
			)
			escalated.tier shouldBe PolicyTier.ACTIVE
			escalated.transitionReason shouldBe PolicyState.TransitionReason.ACCUMULATOR_ESCALATION
			escalated.accumulatorValue shouldBe 50.0
			escalated.detectedActivity shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `copy simulating de-escalation`() {
			val active = PolicyState(
				tier = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.ACCUMULATOR_ESCALATION,
				accumulatorValue = 120.0,
			)
			val deescalated = active.copy(
				tier = PolicyTier.AMBIENT,
				transitionReason = PolicyState.TransitionReason.STILLNESS_DE_ESCALATION,
				accumulatorValue = 20.0,
			)
			deescalated.tier shouldBe PolicyTier.AMBIENT
			deescalated.transitionReason shouldBe PolicyState.TransitionReason.STILLNESS_DE_ESCALATION
			deescalated.accumulatorValue shouldBe 20.0
		}

		@Test
		fun `copy simulating minimum tier lock`() {
			val state = PolicyState(
				tier = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.ACCUMULATOR_ESCALATION,
			)
			val locked = state.copy(
				minimumTierLock = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.TRIP_LOCK,
			)
			locked.minimumTierLock shouldBe PolicyTier.ACTIVE
			locked.transitionReason shouldBe PolicyState.TransitionReason.TRIP_LOCK
		}

		@Test
		fun `copy simulating tier lock release`() {
			val locked = PolicyState(
				tier = PolicyTier.ACTIVE,
				transitionReason = PolicyState.TransitionReason.TRIP_LOCK,
				minimumTierLock = PolicyTier.ACTIVE,
			)
			val unlocked = locked.copy(
				minimumTierLock = null,
				transitionReason = PolicyState.TransitionReason.TRIP_UNLOCK,
			)
			unlocked.minimumTierLock shouldBe null
			unlocked.transitionReason shouldBe PolicyState.TransitionReason.TRIP_UNLOCK
		}
	}
}
