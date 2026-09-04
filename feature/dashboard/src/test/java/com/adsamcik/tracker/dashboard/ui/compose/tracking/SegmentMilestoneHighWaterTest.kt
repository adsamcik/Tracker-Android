package com.adsamcik.tracker.dashboard.ui.compose.tracking

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SegmentMilestoneHighWaterTest {
	@Test
	fun `corrections and null settlement cannot replay a reached step milestone`() {
		val highWater = SegmentMilestoneHighWater()
		highWater.activate(1L)

		highWater.record(0, 1L, 0).steps shouldBe false
		highWater.record(0, 2L, 0).steps shouldBe true
		highWater.record(0, null, 0).steps shouldBe false
		highWater.record(0, 1L, 0).steps shouldBe false
		highWater.record(0, 2L, 0).steps shouldBe false
	}

	@Test
	fun `exact segment transition resets the milestone baseline`() {
		val highWater = SegmentMilestoneHighWater()
		highWater.activate(1L)
		highWater.record(0, 1L, 0).steps shouldBe false
		highWater.record(0, 2L, 0).steps shouldBe true

		highWater.activate(2L)
		highWater.record(0, 1L, 0).steps shouldBe false
		highWater.record(0, 2L, 0).steps shouldBe true
	}
}
