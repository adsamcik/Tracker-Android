package com.adsamcik.tracker.shared.base.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TrackerSession - in-memory tracking session model")
class TrackerSessionTest {

	@Nested
	@DisplayName("Default construction")
	inner class DefaultConstruction {
		@Test
		fun `default values are zero or false`() {
			val session = TrackerSession()
			session.id shouldBe 0L
			session.start shouldBe 0L
			session.end shouldBe 0L
			session.isUserInitiated shouldBe false
			session.collections shouldBe 0
			session.distanceInM shouldBe 0f
			session.distanceOnFootInM shouldBe 0f
			session.distanceInVehicleInM shouldBe 0f
			session.steps shouldBe 0
			session.sessionActivityId shouldBe null
		}
	}

	@Nested
	@DisplayName("Parameterized construction")
	inner class ParameterizedConstruction {
		@Test
		fun `all parameters are stored`() {
			val session = TrackerSession(
				id = 1L,
				start = 100L,
				end = 200L,
				isUserInitiated = true,
				collections = 10,
				distanceInM = 1000f,
				distanceOnFootInM = 500f,
				distanceInVehicleInM = 500f,
				steps = 1500,
				sessionActivityId = 42L
			)
			session.id shouldBe 1L
			session.start shouldBe 100L
			session.end shouldBe 200L
			session.isUserInitiated shouldBe true
			session.collections shouldBe 10
			session.distanceInM shouldBe 1000f
			session.distanceOnFootInM shouldBe 500f
			session.distanceInVehicleInM shouldBe 500f
			session.steps shouldBe 1500
			session.sessionActivityId shouldBe 42L
		}
	}

	@Nested
	@DisplayName("Mutable properties")
	inner class MutableProperties {
		@Test
		fun `properties can be modified`() {
			val session = TrackerSession()
			session.id = 5L
			session.start = 100L
			session.end = 200L
			session.isUserInitiated = true
			session.collections = 3
			session.distanceInM = 500f
			session.steps = 100
			session.sessionActivityId = 10L

			session.id shouldBe 5L
			session.start shouldBe 100L
			session.end shouldBe 200L
			session.isUserInitiated shouldBe true
			session.collections shouldBe 3
			session.distanceInM shouldBe 500f
			session.steps shouldBe 100
			session.sessionActivityId shouldBe 10L
		}
	}

	@Nested
	@DisplayName("Companion constants")
	inner class CompanionConstants {
		@Test
		fun `ACTION_SESSION_STARTED is correct`() {
			TrackerSession.ACTION_SESSION_STARTED shouldBe "com.adsamcik.tracker.intent.action.SESSION_START"
		}

		@Test
		fun `ACTION_SESSION_ENDED is correct`() {
			TrackerSession.ACTION_SESSION_ENDED shouldBe "com.adsamcik.tracker.intent.action.SESSION_END"
		}

		@Test
		fun `ACTION_SESSION_FINAL is correct`() {
			TrackerSession.ACTION_SESSION_FINAL shouldBe "com.adsamcik.tracker.intent.action.SESSION_FINAL"
		}

		@Test
		fun `RECEIVER_SESSION_ID is correct`() {
			TrackerSession.RECEIVER_SESSION_ID shouldBe "id"
		}

		@Test
		fun `RECEIVER_SESSION_IS_NEW is correct`() {
			TrackerSession.RECEIVER_SESSION_IS_NEW shouldBe "isNew"
		}

		@Test
		fun `RECEIVER_SESSION_RESUME_TIMEOUT is correct`() {
			TrackerSession.RECEIVER_SESSION_RESUME_TIMEOUT shouldBe "resumeTimeout"
		}
	}
}
