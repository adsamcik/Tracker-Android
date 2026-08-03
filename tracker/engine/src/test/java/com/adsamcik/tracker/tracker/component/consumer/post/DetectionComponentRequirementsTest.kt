package com.adsamcik.tracker.tracker.component.consumer.post

import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DetectionComponentRequirementsTest {
	@Test
	fun `ski detection runs only with pressure`() {
		SkiTrackingComponent().requiredData shouldBe listOf(TrackerComponentRequirement.PRESSURE)
	}

	@Test
	fun `flight detection runs only with pressure`() {
		PlaneTrackingComponent().requiredData shouldBe listOf(TrackerComponentRequirement.PRESSURE)
	}

	@Test
	fun `sailing detection runs only with location`() {
		SailingTrackingComponent().requiredData shouldBe listOf(TrackerComponentRequirement.LOCATION)
	}
}
