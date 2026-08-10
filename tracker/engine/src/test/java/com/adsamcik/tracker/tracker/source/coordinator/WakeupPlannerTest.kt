package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class WakeupPlannerTest {
	@Test
	fun `compatible source deadlines share one wakeup`() {
		val result = WakeupPlanner().plan(
			requests = listOf(
				request("wifi", SourceKind.WIFI, 1_000, 2_000),
				request("cell", SourceKind.CELL, 1_500, 3_000),
				request("later", SourceKind.CELL, 4_000, 5_000),
			),
			nowElapsedRealtimeMs = 1_500,
		)

		result.size shouldBe 2
		result.first().executeAtElapsedRealtimeMs shouldBe 1_500L
		result.first().requests.map(WakeupRequest::id) shouldContainExactly listOf("cell", "wifi")
	}

	private fun request(id: String, source: SourceKind, earliest: Long, latest: Long) = WakeupRequest(
		id = id,
		source = source,
		earliestElapsedRealtimeMs = earliest,
		latestElapsedRealtimeMs = latest,
		priority = WakeupPriority.NORMAL,
	)
}

