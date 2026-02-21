package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.challenge.data.ChallengeInstanceNew
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ChallengeInstanceNew] progress calculations and property exposure.
 */
@DisplayName("ChallengeInstanceNew")
class ChallengeInstanceTest {

	private val processor: ChallengeProcessor = mockk(relaxed = true)

	private fun createInstance(
		type: ChallengeType = ChallengeType.Step,
		startTime: Long = 1_000_000L,
		endTime: Long = 2_000_000L,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM,
		requiredValue: Double = 10_000.0,
		currentValue: Double = 0.0,
		isCompleted: Boolean = false,
	): ChallengeInstanceNew {
		val entity = ChallengeEntity(
			id = 1L,
			type = type,
			startTime = startTime,
			endTime = endTime,
			difficulty = difficulty,
			requiredValue = requiredValue,
			currentValue = currentValue,
			isCompleted = isCompleted,
		)
		return ChallengeInstanceNew(entity, processor)
	}

	@Nested
	@DisplayName("Progress calculation")
	inner class ProgressTests {

		@Test
		fun `progress is 0 when currentValue is 0`() {
			createInstance(requiredValue = 10_000.0, currentValue = 0.0).progress shouldBe 0.0
		}

		@Test
		fun `progress is 0_5 when currentValue is half of requiredValue`() {
			createInstance(requiredValue = 10_000.0, currentValue = 5_000.0).progress shouldBe 0.5
		}

		@Test
		fun `progress is 1_0 when currentValue equals requiredValue`() {
			createInstance(requiredValue = 10_000.0, currentValue = 10_000.0).progress shouldBe 1.0
		}

		@Test
		fun `progress is clamped to 1_0 when currentValue exceeds requiredValue`() {
			createInstance(requiredValue = 10_000.0, currentValue = 15_000.0).progress shouldBe 1.0
		}

		@Test
		fun `progress is 0_0 when requiredValue is 0`() {
			createInstance(requiredValue = 0.0, currentValue = 100.0).progress shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("Completion and expiry")
	inner class StateTests {

		@Test
		fun `isCompleted reflects entity field when true`() {
			createInstance(isCompleted = true).isCompleted shouldBe true
		}

		@Test
		fun `isCompleted reflects entity field when false`() {
			createInstance(isCompleted = false).isCompleted shouldBe false
		}

		@Test
		fun `isExpired is true when endTime is in the past`() {
			createInstance(endTime = 1L).isExpired shouldBe true
		}

		@Test
		fun `isExpired is false when endTime is in the future`() {
			createInstance(endTime = Long.MAX_VALUE).isExpired shouldBe false
		}
	}

	@Nested
	@DisplayName("Entity properties exposed")
	inner class PropertyTests {

		@Test
		fun `type is exposed from entity`() {
			createInstance(type = ChallengeType.Explorer).entity.type shouldBe ChallengeType.Explorer
		}

		@Test
		fun `difficulty is exposed from entity`() {
			createInstance(difficulty = ChallengeDifficulty.HARD).entity.difficulty shouldBe ChallengeDifficulty.HARD
		}

		@Test
		fun `startTime and endTime are exposed from entity`() {
			val instance = createInstance(startTime = 500L, endTime = 1500L)
			instance.entity.startTime shouldBe 500L
			instance.entity.endTime shouldBe 1500L
		}
	}
}
