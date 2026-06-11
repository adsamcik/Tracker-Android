package com.adsamcik.tracker.tracker.policy

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("PolicyTierMapper")
class PolicyTierMapperTest {

	@Nested
	@DisplayName("toTrackingPolicy")
	inner class ToTrackingPolicy {

		@Test
		fun `OFF maps to PASSIVE_LOW`() {
			PolicyTierMapper.toTrackingPolicy(PolicyTier.OFF) shouldBe TrackingPolicy.PASSIVE_LOW
		}

		@Test
		fun `AMBIENT maps to PASSIVE_LOW`() {
			PolicyTierMapper.toTrackingPolicy(PolicyTier.AMBIENT) shouldBe TrackingPolicy.PASSIVE_LOW
		}

		@Test
		fun `ACTIVE maps to ACTIVE_MODERATE`() {
			PolicyTierMapper.toTrackingPolicy(PolicyTier.ACTIVE) shouldBe TrackingPolicy.ACTIVE_MODERATE
		}

		@Test
		fun `PRECISION maps to ACTIVE_ELEVATED`() {
			PolicyTierMapper.toTrackingPolicy(PolicyTier.PRECISION) shouldBe TrackingPolicy.ACTIVE_ELEVATED
		}

		@ParameterizedTest
		@EnumSource(PolicyTier::class)
		fun `all PolicyTier values are handled`(tier: PolicyTier) {
			// Should not throw - exhaustive mapping
			PolicyTierMapper.toTrackingPolicy(tier)
		}
	}

	@Nested
	@DisplayName("toTier")
	inner class ToTier {

		@Test
		fun `PASSIVE_LOW maps to AMBIENT`() {
			PolicyTierMapper.toTier(TrackingPolicy.PASSIVE_LOW) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `MOVEMENT_SUSPECTED maps to AMBIENT`() {
			PolicyTierMapper.toTier(TrackingPolicy.MOVEMENT_SUSPECTED) shouldBe PolicyTier.AMBIENT
		}

		@Test
		fun `ACTIVE_MODERATE maps to ACTIVE`() {
			PolicyTierMapper.toTier(TrackingPolicy.ACTIVE_MODERATE) shouldBe PolicyTier.ACTIVE
		}

		@Test
		fun `ACTIVE_ELEVATED maps to PRECISION`() {
			PolicyTierMapper.toTier(TrackingPolicy.ACTIVE_ELEVATED) shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `USER_INITIATED maps to PRECISION`() {
			PolicyTierMapper.toTier(TrackingPolicy.USER_INITIATED) shouldBe PolicyTier.PRECISION
		}

		@ParameterizedTest
		@EnumSource(TrackingPolicy::class)
		fun `all TrackingPolicy values are handled`(policy: TrackingPolicy) {
			// Should not throw - exhaustive mapping
			PolicyTierMapper.toTier(policy)
		}
	}

	@Nested
	@DisplayName("toDetectedActivityType")
	inner class ToDetectedActivityType {

		@Test
		fun `code 0 (IN_VEHICLE) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(0) shouldBe DetectedActivityType.IN_VEHICLE
		}

		@Test
		fun `code 1 (ON_BICYCLE) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(1) shouldBe DetectedActivityType.ON_BICYCLE
		}

		@Test
		fun `code 2 (ON_FOOT) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(2) shouldBe DetectedActivityType.ON_FOOT
		}

		@Test
		fun `code 3 (STILL) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(3) shouldBe DetectedActivityType.STILL
		}

		@Test
		fun `code 4 (unknown in Play Services) maps to UNKNOWN`() {
			PolicyTierMapper.toDetectedActivityType(4) shouldBe DetectedActivityType.UNKNOWN
		}

		@Test
		fun `code 5 (TILTING) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(5) shouldBe DetectedActivityType.TILTING
		}

		@Test
		fun `code 7 (WALKING) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(7) shouldBe DetectedActivityType.WALKING
		}

		@Test
		fun `code 8 (RUNNING) maps correctly`() {
			PolicyTierMapper.toDetectedActivityType(8) shouldBe DetectedActivityType.RUNNING
		}

		@Test
		fun `code 6 (undefined gap) maps to UNKNOWN`() {
			PolicyTierMapper.toDetectedActivityType(6) shouldBe DetectedActivityType.UNKNOWN
		}

		@Test
		fun `negative code maps to UNKNOWN`() {
			PolicyTierMapper.toDetectedActivityType(-1) shouldBe DetectedActivityType.UNKNOWN
		}

		@Test
		fun `large code maps to UNKNOWN`() {
			PolicyTierMapper.toDetectedActivityType(999) shouldBe DetectedActivityType.UNKNOWN
		}
	}

	@Nested
	@DisplayName("Round-trip consistency")
	inner class RoundTrip {

		@Test
		fun `PASSIVE_LOW survives round-trip through tier`() {
			val tier = PolicyTierMapper.toTier(TrackingPolicy.PASSIVE_LOW)
			val policy = PolicyTierMapper.toTrackingPolicy(tier)
			policy shouldBe TrackingPolicy.PASSIVE_LOW
		}

		@Test
		fun `ACTIVE_MODERATE survives round-trip through tier`() {
			val tier = PolicyTierMapper.toTier(TrackingPolicy.ACTIVE_MODERATE)
			val policy = PolicyTierMapper.toTrackingPolicy(tier)
			policy shouldBe TrackingPolicy.ACTIVE_MODERATE
		}

		@Test
		fun `ACTIVE_ELEVATED survives round-trip through tier`() {
			val tier = PolicyTierMapper.toTier(TrackingPolicy.ACTIVE_ELEVATED)
			val policy = PolicyTierMapper.toTrackingPolicy(tier)
			policy shouldBe TrackingPolicy.ACTIVE_ELEVATED
		}
	}
}
