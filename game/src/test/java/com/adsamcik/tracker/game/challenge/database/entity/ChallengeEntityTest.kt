package com.adsamcik.tracker.game.challenge.database.entity

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ChallengeEntity")
class ChallengeEntityTest {

	@Nested
	@DisplayName("Defaults")
	inner class Defaults {
		@Test
		fun `id auto-generates from 0`() {
			val entity = ChallengeEntity(
				type = ChallengeType.Step,
				startTime = 0L,
				endTime = 1000L,
				difficulty = ChallengeDifficulty.MEDIUM,
				requiredValue = 100.0,
			)
			entity.id shouldBe 0
		}

		@Test
		fun `currentValue defaults to 0`() {
			val entity = ChallengeEntity(
				type = ChallengeType.Step,
				startTime = 0L,
				endTime = 1000L,
				difficulty = ChallengeDifficulty.MEDIUM,
				requiredValue = 100.0,
			)
			entity.currentValue shouldBe 0.0
		}

		@Test
		fun `isCompleted defaults to false`() {
			val entity = ChallengeEntity(
				type = ChallengeType.Step,
				startTime = 0L,
				endTime = 1000L,
				difficulty = ChallengeDifficulty.MEDIUM,
				requiredValue = 100.0,
			)
			entity.isCompleted shouldBe false
		}

		@Test
		fun `extraJson defaults to null`() {
			val entity = ChallengeEntity(
				type = ChallengeType.Step,
				startTime = 0L,
				endTime = 1000L,
				difficulty = ChallengeDifficulty.MEDIUM,
				requiredValue = 100.0,
			)
			entity.extraJson shouldBe null
		}
	}

	@Nested
	@DisplayName("Copy")
	inner class Copy {
		@Test
		fun `copy updates only specified fields`() {
			val original = ChallengeEntity(
				id = 5,
				type = ChallengeType.WalkDistance,
				startTime = 100L,
				endTime = 200L,
				difficulty = ChallengeDifficulty.HARD,
				requiredValue = 5000.0,
				currentValue = 1000.0,
			)
			val updated = original.copy(currentValue = 2000.0, isCompleted = true)
			updated.id shouldBe 5
			updated.type shouldBe ChallengeType.WalkDistance
			updated.currentValue shouldBe 2000.0
			updated.isCompleted shouldBe true
			updated.requiredValue shouldBe 5000.0
		}
	}

	@Nested
	@DisplayName("All challenge types")
	inner class AllTypes {
		@Test
		fun `entity can be created for every ChallengeType`() {
			ChallengeType.entries.forEach { type ->
				val entity = ChallengeEntity(
					type = type,
					startTime = 0L,
					endTime = 1000L,
					difficulty = ChallengeDifficulty.MEDIUM,
					requiredValue = 100.0,
				)
				entity.type shouldBe type
			}
		}

		@Test
		fun `entity can be created for every difficulty`() {
			ChallengeDifficulty.entries.forEach { difficulty ->
				val entity = ChallengeEntity(
					type = ChallengeType.Step,
					startTime = 0L,
					endTime = 1000L,
					difficulty = difficulty,
					requiredValue = 100.0,
				)
				entity.difficulty shouldBe difficulty
			}
		}
	}
}
