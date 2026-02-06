package com.adsamcik.tracker.game.repository

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for game repository data classes.
 */
@DisplayName("Game Data Classes")
class GameDataTest {

	@Nested
	@DisplayName("StepsSummaryData")
	inner class StepsSummaryDataTests {

		@Test
		fun `data class equality works correctly`() {
			val a = StepsSummaryData(stepsToday = 500, stepsWeek = 3000, goalDay = 10_000, goalWeek = 50_000)
			val b = StepsSummaryData(stepsToday = 500, stepsWeek = 3000, goalDay = 10_000, goalWeek = 50_000)
			a shouldBe b
		}

		@Test
		fun `data class inequality on different steps`() {
			val a = StepsSummaryData(stepsToday = 500, stepsWeek = 3000, goalDay = 10_000, goalWeek = 50_000)
			val b = StepsSummaryData(stepsToday = 600, stepsWeek = 3000, goalDay = 10_000, goalWeek = 50_000)
			a shouldNotBe b
		}

		@Test
		fun `copy preserves unchanged fields`() {
			val original = StepsSummaryData(stepsToday = 500, stepsWeek = 3000, goalDay = 10_000, goalWeek = 50_000)
			val updated = original.copy(stepsToday = 1000)
			updated.stepsToday shouldBe 1000
			updated.stepsWeek shouldBe 3000
			updated.goalDay shouldBe 10_000
			updated.goalWeek shouldBe 50_000
		}
	}

	@Nested
	@DisplayName("ChallengeData")
	inner class ChallengeDataTests {

		@Test
		fun `data class stores all fields`() {
			val data = ChallengeData(id = 42L, title = "Walk 5km", description = "Walk 5 kilometers", progress = 0.75f)
			data.id shouldBe 42L
			data.title shouldBe "Walk 5km"
			data.description shouldBe "Walk 5 kilometers"
			data.progress shouldBe 0.75f
		}

		@Test
		fun `progress at boundary values`() {
			val zero = ChallengeData(id = 1L, title = "t", description = "d", progress = 0f)
			zero.progress shouldBe 0f

			val full = ChallengeData(id = 2L, title = "t", description = "d", progress = 1f)
			full.progress shouldBe 1f
		}

		@Test
		fun `equality based on all fields`() {
			val a = ChallengeData(id = 1L, title = "Walk", description = "Walk far", progress = 0.5f)
			val b = ChallengeData(id = 1L, title = "Walk", description = "Walk far", progress = 0.5f)
			a shouldBe b
		}
	}
}
