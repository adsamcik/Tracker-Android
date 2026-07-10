package com.adsamcik.tracker.tracker.component.consumer.post

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.Test

class NotificationUpdateGateTest {

	private val gate = NotificationUpdateGate(maxRefreshIntervalNanos = 60_000_000_000L)
	private val payload = NotificationPayload(title = "Tracking", text = "1 km")

	@Test
	fun `suppresses unchanged payload before refresh deadline`() {
		gate.shouldUpdate(payload, elapsedRealtimeNanos = 1L).shouldBeTrue()

		gate.shouldUpdate(payload, elapsedRealtimeNanos = 30_000_000_001L).shouldBeFalse()
	}

	@Test
	fun `updates immediately when visible content changes`() {
		gate.shouldUpdate(payload, elapsedRealtimeNanos = 1L).shouldBeTrue()

		gate.shouldUpdate(
			payload.copy(text = "2 km"),
			elapsedRealtimeNanos = 2L,
		).shouldBeTrue()
	}

	@Test
	fun `refreshes unchanged payload after maximum interval`() {
		gate.shouldUpdate(payload, elapsedRealtimeNanos = 1L).shouldBeTrue()

		gate.shouldUpdate(payload, elapsedRealtimeNanos = 60_000_000_001L).shouldBeTrue()
	}

	@Test
	fun `reset forces the next payload to update`() {
		gate.shouldUpdate(payload, elapsedRealtimeNanos = 1L).shouldBeTrue()
		gate.reset()

		gate.shouldUpdate(payload, elapsedRealtimeNanos = 2L).shouldBeTrue()
	}
}
