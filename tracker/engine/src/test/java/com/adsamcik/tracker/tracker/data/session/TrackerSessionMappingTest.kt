package com.adsamcik.tracker.tracker.data.session

import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackerSessionMappingTest {
	@Test
	fun `maps every persisted session field into the API snapshot`() {
		val entity = TrackerSession(
			id = 7L,
			start = 100L,
			end = 200L,
			isUserInitiated = true,
			collections = 11,
			distanceInM = 12.5f,
			distanceOnFootInM = 8.5f,
			distanceInVehicleInM = 4f,
			steps = 42,
			sessionActivityId = 3L,
		)

		entity.toSnapshot() shouldBe TrackerSessionSnapshot(
			id = 7L,
			start = 100L,
			end = 200L,
			isUserInitiated = true,
			collections = 11,
			distanceInM = 12.5f,
			distanceOnFootInM = 8.5f,
			distanceInVehicleInM = 4f,
			steps = 42,
			sessionActivityId = 3L,
		)
	}
}
