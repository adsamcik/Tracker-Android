package com.adsamcik.tracker.game.challenge.database.entity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Entity data classes")
class EntityDataClassesTest {

	@Nested
	@DisplayName("PlayerProfileEntity")
	inner class PlayerProfileEntityTest {
		@Test
		fun `default values are correct`() {
			val entity = PlayerProfileEntity()
			entity.id shouldBe 1
			entity.totalXp shouldBe 0
			entity.level shouldBe 1
			entity.xpIntoCurrentLevel shouldBe 0
			entity.xpForNextLevel shouldBe 30
		}

		@Test
		fun `copy preserves non-modified fields`() {
			val entity = PlayerProfileEntity(totalXp = 100, level = 3)
			val copied = entity.copy(level = 5)
			copied.totalXp shouldBe 100
			copied.level shouldBe 5
			copied.id shouldBe 1
		}

		@Test
		fun `equality based on all fields`() {
			val a = PlayerProfileEntity(totalXp = 50, level = 2)
			val b = PlayerProfileEntity(totalXp = 50, level = 2)
			a shouldBe b
		}
	}

	@Nested
	@DisplayName("ChallengeStreakEntity")
	inner class ChallengeStreakEntityTest {
		@Test
		fun `default values are all zero`() {
			val entity = ChallengeStreakEntity()
			entity.id shouldBe 1
			entity.currentCount shouldBe 0
			entity.bestCount shouldBe 0
			entity.lastCompletionTime shouldBe 0
			entity.freezeCount shouldBe 0
		}

		@Test
		fun `copy updates specific fields`() {
			val entity = ChallengeStreakEntity(currentCount = 5, bestCount = 10)
			val updated = entity.copy(currentCount = 6)
			updated.currentCount shouldBe 6
			updated.bestCount shouldBe 10
		}
	}

	@Nested
	@DisplayName("ChallengePersonalRecordEntity")
	inner class ChallengePersonalRecordEntityTest {
		@Test
		fun `auto-generate id defaults to 0`() {
			val entity = ChallengePersonalRecordEntity(
				challengeType = "Step",
				metric = "HIGHEST_VALUE",
				value = 10000.0,
				achievedAt = 1000L,
			)
			entity.id shouldBe 0
		}

		@Test
		fun `history id is nullable`() {
			val entity = ChallengePersonalRecordEntity(
				challengeType = "Step",
				metric = "FASTEST_COMPLETION",
				value = 0.5,
				historyId = null,
				achievedAt = 2000L,
			)
			entity.historyId shouldBe null
		}
	}

	@Nested
	@DisplayName("ChallengeHistoryEntity")
	inner class ChallengeHistoryEntityTest {
		@Test
		fun `defaults for optional fields`() {
			val entity = ChallengeHistoryEntity(
				challengeType = "WalkDistance",
				difficulty = "MEDIUM",
				startTime = 1000L,
				endTime = 2000L,
				outcome = "COMPLETED",
				progressValue = 5000.0,
				targetValue = 10000.0,
			)
			entity.id shouldBe 0
			entity.completedAt shouldBe null
			entity.medal shouldBe null
			entity.xpAwarded shouldBe 0
			entity.originalChallengeId shouldBe null
		}

		@Test
		fun `full construction`() {
			val entity = ChallengeHistoryEntity(
				id = 5,
				challengeType = "Step",
				difficulty = "HARD",
				startTime = 100L,
				endTime = 200L,
				outcome = "COMPLETED",
				completedAt = 150L,
				progressValue = 50000.0,
				targetValue = 50000.0,
				medal = "GOLD",
				xpAwarded = 800,
				originalChallengeId = 3,
			)
			entity.id shouldBe 5
			entity.medal shouldBe "GOLD"
			entity.xpAwarded shouldBe 800
		}
	}

	@Nested
	@DisplayName("XpLedgerEntity")
	inner class XpLedgerEntityTest {
		@Test
		fun `auto-generate id defaults to 0`() {
			val entity = XpLedgerEntity(
				amount = 100,
				source = "SESSION",
				earnedAt = 5000L,
			)
			entity.id shouldBe 0
			entity.sourceId shouldBe null
		}

		@Test
		fun `sourceId can be set`() {
			val entity = XpLedgerEntity(
				amount = 200,
				source = "CHALLENGE",
				sourceId = 42L,
				earnedAt = 5000L,
			)
			entity.sourceId shouldBe 42L
		}
	}
}
