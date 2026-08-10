package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.coordinator.WakeupPlanner
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ConnectivityWakeupPolicyTest {
	@Test
	fun `Wi-Fi and cell attempt windows coalesce into one service wakeup`() {
		val planned = WakeupPlanner().plan(
			listOf(
				sourceWakeupRequest("wifi", SourceKind.WIFI, 1_000, 1_000),
				sourceWakeupRequest("cell", SourceKind.CELL, 1_500, 1_000),
			),
			nowElapsedRealtimeMs = 1_500,
		)

		assertEquals(1, planned.size)
		assertEquals(setOf("wifi", "cell"), planned.single().requests.mapTo(mutableSetOf()) { it.id })
	}

	@Test
	fun `backoff is exponential capped and resets after provider success`() {
		val policy = RetryBackoff(1_000, 5_000, 2.0)
		val one = RuntimeBackoffState().failed()
		val two = one.failed()
		val many = (1..20).fold(RuntimeBackoffState()) { state, _ -> state.failed() }

		assertEquals(1_000, one.delayMs(policy))
		assertEquals(2_000, two.delayMs(policy))
		assertEquals(5_000, many.delayMs(policy))
		assertEquals(0, many.succeeded().delayMs(policy))
		assertTrue(many.consecutiveFailures > two.consecutiveFailures)
	}

	@Test
	fun `provider attempts never violate the selected minimum interval or backoff`() {
		val deadline = nextAttemptDeadlineMs(
			nowElapsedRealtimeMs = 11_000,
			lastAttemptElapsedRealtimeMs = 10_000,
			minimumIntervalMs = 60_000,
			backoffDelayMs = 120_000,
		)

		assertEquals(131_000, deadline)
		assertTrue(deadline >= 70_000)
	}
}
