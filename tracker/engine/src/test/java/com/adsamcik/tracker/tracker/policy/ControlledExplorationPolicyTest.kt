package com.adsamcik.tracker.tracker.policy

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlledExplorationPolicyTest {

	@Test
	fun `eligible production decision has exact zero propensity and is never selected`() {
		val decision = ProductionControlledExplorationPolicy.decide(
			ControlledExplorationContext(
				policy = TrackingPolicy.PASSIVE_LOW,
				isUserInitiated = false,
			)
		)

		assertTrue(decision.eligible)
		assertEquals(0L, decision.propensity.toRawBits())
		assertFalse(decision.selected)
		assertEquals(ControlledExplorationReason.PRODUCTION_DISABLED, decision.reason)
	}

	@Test
	fun `active and user-initiated contexts are not eligible`() {
		val contexts = listOf(
			ControlledExplorationContext(
				policy = TrackingPolicy.ACTIVE_MODERATE,
				isUserInitiated = false,
			),
			ControlledExplorationContext(
				policy = TrackingPolicy.PASSIVE_LOW,
				isUserInitiated = true,
			),
		)

		contexts.forEach { context ->
			val decision = ProductionControlledExplorationPolicy.decide(context)
			assertFalse(decision.eligible)
			assertEquals(0L, decision.propensity.toRawBits())
			assertFalse(decision.selected)
			assertEquals(ControlledExplorationReason.NOT_ELIGIBLE, decision.reason)
		}
	}

	@Test
	fun `production decision is deterministic`() {
		val context = ControlledExplorationContext(
			policy = TrackingPolicy.PASSIVE_LOW,
			isUserInitiated = false,
		)

		val first = ProductionControlledExplorationPolicy.decide(context)
		repeat(10) {
			assertEquals(first, ProductionControlledExplorationPolicy.decide(context))
		}
	}

	@Test
	fun `decision contract rejects selection at zero propensity`() {
		assertFailsWith<IllegalArgumentException> {
			ControlledExplorationDecision(
				eligible = true,
				propensity = 0.0,
				selected = true,
				reason = ControlledExplorationReason.PRODUCTION_DISABLED,
			)
		}
	}
}
