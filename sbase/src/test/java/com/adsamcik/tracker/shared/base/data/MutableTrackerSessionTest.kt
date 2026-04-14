package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MutableTrackerSession - mutable session with convenience constructors")
class MutableTrackerSessionTest {

	@Nested
	@DisplayName("Short constructor (start, isUserInitiated)")
	inner class ShortConstructor {
		@Test
		fun `start and end are the same`() {
			val session = MutableTrackerSession(start = 1000L, isUserInitiated = true)
			session.start shouldBe 1000L
			session.end shouldBe 1000L
		}

		@Test
		fun `id defaults to zero`() {
			val session = MutableTrackerSession(start = 1000L, isUserInitiated = false)
			session.id shouldBe 0L
		}

		@Test
		fun `isUserInitiated is stored`() {
			MutableTrackerSession(start = 1000L, isUserInitiated = true).isUserInitiated shouldBe true
			MutableTrackerSession(start = 1000L, isUserInitiated = false).isUserInitiated shouldBe false
		}

		@Test
		fun `numeric fields default to zero`() {
			val session = MutableTrackerSession(start = 1000L, isUserInitiated = true)
			session.collections shouldBe 0
			session.distanceInM shouldBe 0f
			session.distanceOnFootInM shouldBe 0f
			session.distanceInVehicleInM shouldBe 0f
			session.steps shouldBe 0
		}
	}

	@Nested
	@DisplayName("Copy constructor from TrackerSession")
	inner class CopyConstructor {
		@Test
		fun `copies all fields from TrackerSession`() {
			val source = TrackerSession(
				id = 5L,
				start = 100L,
				end = 200L,
				isUserInitiated = true,
				collections = 10,
				distanceInM = 1000f,
				distanceOnFootInM = 500f,
				distanceInVehicleInM = 300f,
				steps = 2000
			)
			val copy = MutableTrackerSession(source)
			copy.id shouldBe 5L
			copy.start shouldBe 100L
			copy.end shouldBe 200L
			copy.isUserInitiated shouldBe true
			copy.collections shouldBe 10
			copy.distanceInM shouldBe 1000f
			copy.distanceOnFootInM shouldBe 500f
			copy.distanceInVehicleInM shouldBe 300f
			copy.steps shouldBe 2000
		}
	}

	@Nested
	@DisplayName("Mutability")
	inner class Mutability {
		@Test
		fun `all properties are mutable`() {
			val session = MutableTrackerSession(start = 0L, isUserInitiated = false)
			session.id = 10L
			session.start = 100L
			session.end = 200L
			session.isUserInitiated = true
			session.collections = 5
			session.distanceInM = 1500f
			session.distanceOnFootInM = 800f
			session.distanceInVehicleInM = 700f
			session.steps = 3000
			session.sessionActivityId = 42L

			session.id shouldBe 10L
			session.start shouldBe 100L
			session.end shouldBe 200L
			session.isUserInitiated shouldBe true
			session.collections shouldBe 5
			session.distanceInM shouldBe 1500f
			session.distanceOnFootInM shouldBe 800f
			session.distanceInVehicleInM shouldBe 700f
			session.steps shouldBe 3000
			session.sessionActivityId shouldBe 42L
		}
	}
}
