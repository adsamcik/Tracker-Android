package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

/**
 * Unit tests for [BatteryAwarePolicy].
 *
 * Tests the pure [adjustForBatteryLevel] function directly (no Android
 * dependency needed). Verifies battery-level–based tier capping at all
 * threshold boundaries.
 */
@DisplayName("BatteryAwarePolicy")
class BatteryAwarePolicyTest {

	private val subject = BatteryAwarePolicy(mockk(relaxed = true))

	@Nested
	@DisplayName("Critical battery (≤10%)")
	inner class CriticalBattery {

		@Test
		fun `PRECISION is forced to AMBIENT at 10 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.PRECISION, 10) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `ACTIVE is forced to AMBIENT at 5 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.ACTIVE, 5) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `AMBIENT stays AMBIENT at 1 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.AMBIENT, 1) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `OFF stays OFF at 0 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.OFF, 0) shouldBe PolicyTier.OFF
		}
	}

	@Nested
	@DisplayName("Low battery (11-20%)")
	inner class LowBattery {

		@Test
		fun `PRECISION is capped to AMBIENT at 15 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.PRECISION, 15) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `ACTIVE is capped to AMBIENT at 20 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.ACTIVE, 20) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `AMBIENT stays AMBIENT at 11 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.AMBIENT, 11) shouldBe PolicyTier.AMBIENT
		}
	}

	@Nested
	@DisplayName("Medium battery (21-35%)")
	inner class MediumBattery {

		@Test
		fun `PRECISION is capped to ACTIVE at 25 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.PRECISION, 25) shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `ACTIVE stays ACTIVE at 35 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.ACTIVE, 35) shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `AMBIENT stays AMBIENT at 30 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.AMBIENT, 30) shouldBe PolicyTier.AMBIENT
		}
	}

	@Nested
	@DisplayName("Normal battery (>35%)")
	inner class NormalBattery {

		@ParameterizedTest
		@EnumSource(PolicyTier::class)
		fun `all tiers pass through unchanged at 50 percent`(tier: PolicyTier) {
			subject.adjustForBatteryLevel(tier, 50) shouldBe tier
		}

		@ParameterizedTest
		@EnumSource(PolicyTier::class)
		fun `all tiers pass through unchanged at 100 percent`(tier: PolicyTier) {
			subject.adjustForBatteryLevel(tier, 100) shouldBe tier
		}

		@Test
		fun `PRECISION stays PRECISION at 36 percent`() {
			subject.adjustForBatteryLevel(PolicyTier.PRECISION, 36) shouldBe PolicyTier.PRECISION
		}
	}

	@Nested
	@DisplayName("Boundary conditions")
	inner class Boundaries {

		@ParameterizedTest(name = "battery={0}, base={1}, expected={2}")
		@CsvSource(
			"10, PRECISION, AMBIENT",   // Critical boundary
			"11, PRECISION, AMBIENT",   // Low zone start
			"20, PRECISION, AMBIENT",   // Low boundary
			"21, PRECISION, ACTIVE",    // Medium zone start
			"35, PRECISION, ACTIVE",    // Medium boundary
			"36, PRECISION, PRECISION", // Normal zone start
		)
		fun `boundary transitions are correct for PRECISION`(
			level: Int,
			base: PolicyTier,
			expected: PolicyTier,
		) {
			subject.adjustForBatteryLevel(base, level) shouldBe expected
		}
	}

	@Nested
	@DisplayName("Monotonicity guarantee")
	inner class Monotonicity {

		@Test
		fun `result is always less than or equal to base tier`() {
			for (tier in PolicyTier.entries) {
				for (level in 0..100) {
					val result = subject.adjustForBatteryLevel(tier, level)
					assert(result <= tier) {
						"adjustForBatteryLevel($tier, $level) = $result should be ≤ $tier"
					}
				}
			}
		}

		@Test
		fun `higher battery never yields lower tier for same base`() {
			for (tier in PolicyTier.entries) {
				var lastResult = subject.adjustForBatteryLevel(tier, 0)
				for (level in 1..100) {
					val result = subject.adjustForBatteryLevel(tier, level)
					assert(result >= lastResult) {
						"Non-monotonic at level=$level: $result < $lastResult"
					}
					lastResult = result
				}
			}
		}
	}
}
