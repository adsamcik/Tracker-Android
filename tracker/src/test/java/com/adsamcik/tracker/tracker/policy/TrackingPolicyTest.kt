package com.adsamcik.tracker.tracker.policy

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@DisplayName("TrackingPolicy")
class TrackingPolicyTest {

	@Nested
	@DisplayName("Enum completeness")
	inner class EnumCompleteness {

		@Test
		fun `has exactly 5 values`() {
			TrackingPolicy.entries.size shouldBe 5
		}

		@Test
		fun `contains all expected values`() {
			TrackingPolicy.entries shouldContainAll listOf(
				TrackingPolicy.PASSIVE_LOW,
				TrackingPolicy.MOVEMENT_SUSPECTED,
				TrackingPolicy.ACTIVE_MODERATE,
				TrackingPolicy.ACTIVE_ELEVATED,
				TrackingPolicy.USER_INITIATED,
			)
		}

		@ParameterizedTest
		@EnumSource(TrackingPolicy::class)
		fun `each policy has a unique ordinal`(policy: TrackingPolicy) {
			val otherOrdinals = TrackingPolicy.entries
				.filter { it != policy }
				.map { it.ordinal }
			otherOrdinals.contains(policy.ordinal) shouldBe false
		}
	}

	@Nested
	@DisplayName("Ordinal ordering")
	inner class Ordering {

		@Test
		fun `PASSIVE_LOW comes before MOVEMENT_SUSPECTED`() {
			(TrackingPolicy.PASSIVE_LOW.ordinal < TrackingPolicy.MOVEMENT_SUSPECTED.ordinal) shouldBe true
		}

		@Test
		fun `MOVEMENT_SUSPECTED comes before ACTIVE_MODERATE`() {
			(TrackingPolicy.MOVEMENT_SUSPECTED.ordinal < TrackingPolicy.ACTIVE_MODERATE.ordinal) shouldBe true
		}

		@Test
		fun `ACTIVE_MODERATE comes before ACTIVE_ELEVATED`() {
			(TrackingPolicy.ACTIVE_MODERATE.ordinal < TrackingPolicy.ACTIVE_ELEVATED.ordinal) shouldBe true
		}

		@Test
		fun `ACTIVE_ELEVATED comes before USER_INITIATED`() {
			(TrackingPolicy.ACTIVE_ELEVATED.ordinal < TrackingPolicy.USER_INITIATED.ordinal) shouldBe true
		}
	}

	@Nested
	@DisplayName("Name stability")
	inner class NameStability {

		@ParameterizedTest
		@EnumSource(TrackingPolicy::class)
		fun `valueOf round-trip works`(policy: TrackingPolicy) {
			TrackingPolicy.valueOf(policy.name) shouldBe policy
		}
	}
}

@DisplayName("PolicyTransitionReason")
class PolicyTransitionReasonTest {

	@Nested
	@DisplayName("Enum completeness")
	inner class EnumCompleteness {

		@Test
		fun `has exactly 9 values`() {
			PolicyTransitionReason.entries.size shouldBe 9
		}

		@Test
		fun `contains all expected values`() {
			PolicyTransitionReason.entries shouldContainAll listOf(
				PolicyTransitionReason.STEP_RATE_THRESHOLD,
				PolicyTransitionReason.ACTIVITY_TRANSITION,
				PolicyTransitionReason.LOCATION_CHANGE,
				PolicyTransitionReason.MOVEMENT_COOLDOWN,
				PolicyTransitionReason.USER_START,
				PolicyTransitionReason.USER_STOP,
				PolicyTransitionReason.SERVICE_START,
				PolicyTransitionReason.SERVICE_STOP,
				PolicyTransitionReason.FALLBACK,
			)
		}

		@ParameterizedTest
		@EnumSource(PolicyTransitionReason::class)
		fun `valueOf round-trip works`(reason: PolicyTransitionReason) {
			PolicyTransitionReason.valueOf(reason.name) shouldBe reason
		}
	}
}
