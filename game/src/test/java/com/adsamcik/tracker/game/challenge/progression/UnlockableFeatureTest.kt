package com.adsamcik.tracker.game.challenge.progression

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("UnlockableFeature")
class UnlockableFeatureTest {

	@Nested
	@DisplayName("Required levels")
	inner class RequiredLevels {
		@Test
		fun `BASE_CHALLENGES requires level 1`() {
			UnlockableFeature.BASE_CHALLENGES.requiredLevel shouldBe 1
		}

		@Test
		fun `PERSONAL_RECORDS requires level 2`() {
			UnlockableFeature.PERSONAL_RECORDS.requiredLevel shouldBe 2
		}

		@Test
		fun `OUTRUN_MINI_GAME requires level 3`() {
			UnlockableFeature.OUTRUN_MINI_GAME.requiredLevel shouldBe 3
		}

		@Test
		fun `STREAK_FREEZE requires level 4`() {
			UnlockableFeature.STREAK_FREEZE.requiredLevel shouldBe 4
		}

		@Test
		fun `SPEED_CHALLENGE requires level 5`() {
			UnlockableFeature.SPEED_CHALLENGE.requiredLevel shouldBe 5
		}

		@Test
		fun `TERRITORY_MINI_GAME requires level 6`() {
			UnlockableFeature.TERRITORY_MINI_GAME.requiredLevel shouldBe 6
		}

		@Test
		fun `LIFETIME_STATS requires level 7`() {
			UnlockableFeature.LIFETIME_STATS.requiredLevel shouldBe 7
		}

		@Test
		fun `CONSISTENCY_CHALLENGE requires level 8`() {
			UnlockableFeature.CONSISTENCY_CHALLENGE.requiredLevel shouldBe 8
		}

		@Test
		fun `ZEN_WALK_MINI_GAME requires level 9`() {
			UnlockableFeature.ZEN_WALK_MINI_GAME.requiredLevel shouldBe 9
		}

		@Test
		fun `WEEKLY_RANK requires level 10`() {
			UnlockableFeature.WEEKLY_RANK.requiredLevel shouldBe 10
		}
	}

	@Nested
	@DisplayName("Entries")
	inner class Entries {
		@Test
		fun `has exactly 13 features`() {
			UnlockableFeature.entries.size shouldBe 13
		}

		@Test
		fun `covers levels 1 through 10`() {
			val levels = UnlockableFeature.entries.map { it.requiredLevel }.toSet()
			levels shouldBe setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
		}
	}
}
