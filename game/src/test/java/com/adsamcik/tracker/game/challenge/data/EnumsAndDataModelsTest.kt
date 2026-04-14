package com.adsamcik.tracker.game.challenge.data

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Enums and data models")
class EnumsAndDataModelsTest {

	@Nested
	@DisplayName("ChallengeType")
	inner class ChallengeTypeTest {
		@Test
		fun `has exactly 6 entries`() {
			ChallengeType.entries.size shouldBe 6
		}

		@Test
		fun `contains all expected types`() {
			val names = ChallengeType.entries.map { it.name }
			names shouldBe listOf("Explorer", "WalkDistance", "Step", "ActiveTime", "Speed", "Consistency")
		}
	}

	@Nested
	@DisplayName("XpSource")
	inner class XpSourceTest {
		@Test
		fun `has exactly 4 entries`() {
			XpSource.entries.size shouldBe 4
		}

		@Test
		fun `contains SESSION, CHALLENGE, GOAL, MINI_GAME`() {
			val names = XpSource.entries.map { it.name }
			names shouldBe listOf("SESSION", "CHALLENGE", "GOAL", "MINI_GAME")
		}
	}

	@Nested
	@DisplayName("ChallengeOutcome")
	inner class ChallengeOutcomeTest {
		@Test
		fun `has 3 entries`() {
			ChallengeOutcome.entries.size shouldBe 3
		}

		@Test
		fun `COMPLETED, EXPIRED, ABANDONED`() {
			ChallengeOutcome.entries.map { it.name } shouldBe listOf("COMPLETED", "EXPIRED", "ABANDONED")
		}
	}
}
