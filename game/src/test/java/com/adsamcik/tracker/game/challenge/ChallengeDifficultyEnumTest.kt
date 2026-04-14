package com.adsamcik.tracker.game.challenge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ChallengeDifficulty")
class ChallengeDifficultyTest {

	@Nested
	@DisplayName("Entries")
	inner class Entries {
		@Test
		fun `has exactly 5 difficulty levels`() {
			ChallengeDifficulty.entries.size shouldBe 5
		}

		@Test
		fun `ordinal values are sequential`() {
			ChallengeDifficulty.VERY_EASY.ordinal shouldBe 0
			ChallengeDifficulty.EASY.ordinal shouldBe 1
			ChallengeDifficulty.MEDIUM.ordinal shouldBe 2
			ChallengeDifficulty.HARD.ordinal shouldBe 3
			ChallengeDifficulty.VERY_HARD.ordinal shouldBe 4
		}
	}

	@Nested
	@DisplayName("String resources")
	inner class StringResources {
		@Test
		fun `each difficulty has a string resource`() {
			ChallengeDifficulty.entries.forEach { difficulty ->
				// difficultyStringRes should be non-zero (valid resource ID)
				(difficulty.difficultyStringRes != 0) shouldBe true
			}
		}

		@Test
		fun `all difficulties have distinct string resources`() {
			val resources = ChallengeDifficulty.entries.map { it.difficultyStringRes }.toSet()
			resources.size shouldBe 5
		}
	}
}
