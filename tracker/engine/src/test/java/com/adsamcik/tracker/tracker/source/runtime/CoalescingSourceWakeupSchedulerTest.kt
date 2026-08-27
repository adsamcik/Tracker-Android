package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorTelemetry
import com.adsamcik.tracker.tracker.source.coordinator.WakeupPlanner
import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingSourceWakeupSchedulerTest {
	@Test
	fun `one fired timer measures every coalesced source request`() = runTest {
		val telemetry = TrackingCoordinatorTelemetry()
		val scheduler = CoalescingSourceWakeupScheduler(WakeupPlanner(), this, telemetry)
		val originalClock = CoalescingSourceWakeupScheduler.clock
		var actions = 0
		try {
			CoalescingSourceWakeupScheduler.clock = ElapsedRealtimeClock { 1_000L }
			scheduler.schedule(
				sourceWakeupRequest("wifi", SourceKind.WIFI, 1_000L, 500L),
			) { actions++ }
			scheduler.schedule(
				sourceWakeupRequest("cell", SourceKind.CELL, 1_000L, 500L),
			) { actions++ }

			runCurrent()

			actions shouldBe 2
			telemetry.snapshot().sourceTimerWakeupCount shouldBe 1L
			telemetry.snapshot().sourceTimerRequestCount shouldBe 2L
		} finally {
			CoalescingSourceWakeupScheduler.clock = originalClock
		}
	}
}
