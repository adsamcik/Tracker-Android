package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import io.kotest.matchers.shouldBe
import org.junit.Test

class SourcePlanCodecTest {
	private val subject = SourcePlanCodec()

	@Test
	fun `all source plans round trip deterministically`() {
		val plans: List<SourcePlan> = listOf(
			LocationPlan(4, LocationBackend.FRAMEWORK, LocationMode.BALANCED, 5_000, 2_000, 4f, 10_000, null, true),
			ActivityPlan(4, ActivityMode.TRANSITIONS_ONLY, 10_000, 65, setOf(1, 4)),
			StepsPlan(4, true, 15_000, 5_000, false),
			PressurePlan(4, true, 100_000, 2_000_000, 5_000, true),
			WifiPlan(4, WifiMode.ACTIVE_ATTEMPTS, 60_000, 30_000, 120_000, RetryBackoff(5_000, 300_000)),
			CellPlan(4, CellMode.OBSERVE_CHANGES, 120_000, 60_000, setOf(2, 1), RetryBackoff(10_000, 600_000)),
		)

		plans.forEach { plan ->
			val first = subject.encode(plan)
			val second = subject.encode(plan)
			subject.decode(first.bytes) shouldBe plan
			first.bytes.toList() shouldBe second.bytes.toList()
			first.checksum shouldBe second.checksum
		}
	}
}
