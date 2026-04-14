package com.adsamcik.tracker.game.challenge.data

import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.database.entity.ChallengeEntity
import com.adsamcik.tracker.game.challenge.processor.ChallengeProcessor
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ChallengeInstanceNew")
class ChallengeInstanceNewTest {

	private val processor: ChallengeProcessor = mockk(relaxed = true)

	@Nested
	@DisplayName("progress")
	inner class Progress {
		@Test
		fun `progress is 0 when currentValue is 0`() {
			val entity = createEntity(currentValue = 0.0, requiredValue = 100.0)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.progress shouldBe 0.0
		}

		@Test
		fun `progress is 1 when completed`() {
			val entity = createEntity(currentValue = 100.0, requiredValue = 100.0)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.progress shouldBe 1.0
		}

		@Test
		fun `progress is 0_5 at halfway`() {
			val entity = createEntity(currentValue = 50.0, requiredValue = 100.0)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.progress shouldBe 0.5
		}

		@Test
		fun `progress capped at 1 when over-completed`() {
			val entity = createEntity(currentValue = 200.0, requiredValue = 100.0)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.progress shouldBe 1.0
		}

		@Test
		fun `progress is 0 when requiredValue is 0`() {
			val entity = createEntity(currentValue = 50.0, requiredValue = 0.0)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.progress shouldBe 0.0
		}
	}

	@Nested
	@DisplayName("isCompleted")
	inner class IsCompleted {
		@Test
		fun `true when entity is completed`() {
			val entity = createEntity(isCompleted = true)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.isCompleted shouldBe true
		}

		@Test
		fun `false when entity is not completed`() {
			val entity = createEntity(isCompleted = false)
			val instance = ChallengeInstanceNew(entity, processor)
			instance.isCompleted shouldBe false
		}
	}

	private fun createEntity(
		currentValue: Double = 0.0,
		requiredValue: Double = 100.0,
		isCompleted: Boolean = false,
	): ChallengeEntity {
		return ChallengeEntity(
			id = 1L,
			type = ChallengeType.Step,
			difficulty = ChallengeDifficulty.MEDIUM,
			startTime = 0L,
			endTime = Long.MAX_VALUE,
			requiredValue = requiredValue,
			currentValue = currentValue,
			isCompleted = isCompleted,
		)
	}
}
