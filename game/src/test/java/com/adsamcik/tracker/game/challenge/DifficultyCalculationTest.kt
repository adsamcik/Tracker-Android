package com.adsamcik.tracker.game.challenge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ChallengeManager.difficultyFromCompletionRate")
class DifficultyCalculationTest {

	@Nested
	@DisplayName("edge cases")
	inner class EdgeCases {

		@Test
		fun `empty history returns MEDIUM`() {
			ChallengeManager.difficultyFromCompletionRate(emptyList()) shouldBe ChallengeDifficulty.MEDIUM
		}

		@Test
		fun `single completed returns VERY_HARD`() {
			ChallengeManager.difficultyFromCompletionRate(listOf("COMPLETED")) shouldBe ChallengeDifficulty.VERY_HARD
		}

		@Test
		fun `single expired returns VERY_EASY`() {
			ChallengeManager.difficultyFromCompletionRate(listOf("EXPIRED")) shouldBe ChallengeDifficulty.VERY_EASY
		}
	}

	@Nested
	@DisplayName("rate thresholds")
	inner class Thresholds {

		@Test
		fun `100 pct completion returns VERY_HARD`() {
			val outcomes = List(10) { "COMPLETED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.VERY_HARD
		}

		@Test
		fun `80 pct completion returns VERY_HARD`() {
			val outcomes = List(8) { "COMPLETED" } + List(2) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.VERY_HARD
		}

		@Test
		fun `70 pct completion returns HARD`() {
			val outcomes = List(7) { "COMPLETED" } + List(3) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.HARD
		}

		@Test
		fun `50 pct completion returns MEDIUM`() {
			val outcomes = List(5) { "COMPLETED" } + List(5) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.MEDIUM
		}

		@Test
		fun `30 pct completion returns EASY`() {
			val outcomes = List(3) { "COMPLETED" } + List(7) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.EASY
		}

		@Test
		fun `10 pct completion returns VERY_EASY`() {
			val outcomes = List(1) { "COMPLETED" } + List(9) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.VERY_EASY
		}

		@Test
		fun `0 pct completion returns VERY_EASY`() {
			val outcomes = List(10) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.VERY_EASY
		}
	}

	@Nested
	@DisplayName("window limiting")
	inner class WindowLimiting {

		@Test
		fun `only last 10 outcomes are considered`() {
			// Old history: all expired (20 items), recent: all completed (10 items)
			val outcomes = List(20) { "EXPIRED" } + List(10) { "COMPLETED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.VERY_HARD
		}

		@Test
		fun `recent failures override old successes`() {
			val outcomes = List(20) { "COMPLETED" } + List(10) { "EXPIRED" }
			ChallengeManager.difficultyFromCompletionRate(outcomes) shouldBe ChallengeDifficulty.VERY_EASY
		}
	}
}
