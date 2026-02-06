package com.adsamcik.tracker.game.challenge

import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.entity.ActiveTimeChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.ExplorerChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.ActiveTimeChallengeInstance
import com.adsamcik.tracker.game.challenge.data.instance.ExplorerChallengeInstance
import com.adsamcik.tracker.game.challenge.data.instance.StepChallengeInstance
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for challenge instance progress calculations and entry properties.
 * Note: checkCompletionConditions is protected, so we test the equivalent
 * logic through the public entity fields and progress property.
 */
@DisplayName("Challenge Instance Logic")
class ChallengeInstanceTest {

	private fun createEntry(
		type: ChallengeType = ChallengeType.Step,
		startTime: Long = 1_000_000L,
		endTime: Long = 2_000_000L,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM
	): ChallengeEntry = ChallengeEntry(type, startTime, endTime, difficulty).also { it.id = 1L }

	@Nested
	@DisplayName("ChallengeEntry properties")
	inner class EntryTests {

		@Test
		fun `duration is endTime minus startTime via ChallengeInstance`() {
			val entry = createEntry(startTime = 100_000L, endTime = 300_000L)
			val entity = StepChallengeEntity(entry.id, false, 1000, 0)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)
			instance.duration shouldBe 200_000L
		}

		@Test
		fun `startTime and endTime are exposed correctly`() {
			val entry = createEntry(startTime = 500L, endTime = 1500L)
			val entity = StepChallengeEntity(entry.id, false, 100, 0)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)
			instance.startTime shouldBe 500L
			instance.endTime shouldBe 1500L
		}

		@Test
		fun `difficulty is exposed from entry`() {
			val entry = createEntry(difficulty = ChallengeDifficulty.HARD)
			val entity = StepChallengeEntity(entry.id, false, 100, 0)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)
			instance.difficulty shouldBe ChallengeDifficulty.HARD
		}
	}

	@Nested
	@DisplayName("StepChallengeInstance progress")
	inner class StepChallengeTests {

		private fun createStepInstance(
			requiredSteps: Int = 10_000,
			currentSteps: Int = 0
		): StepChallengeInstance {
			val entry = createEntry(type = ChallengeType.Step)
			val entity = StepChallengeEntity(entry.id, false, requiredSteps, currentSteps)
			return StepChallengeInstance(entry, mockk(relaxed = true), entity)
		}

		@Test
		fun `progress is zero when no steps taken`() {
			createStepInstance(requiredSteps = 10_000, currentSteps = 0).progress shouldBe 0.0
		}

		@Test
		fun `progress is 0_5 when half steps completed`() {
			createStepInstance(requiredSteps = 10_000, currentSteps = 5_000).progress shouldBe 0.5
		}

		@Test
		fun `progress exceeds 1_0 when steps exceed requirement`() {
			createStepInstance(requiredSteps = 10_000, currentSteps = 15_000).progress shouldBeGreaterThan 1.0
		}

		@Test
		fun `progress is exactly 1_0 at completion`() {
			createStepInstance(requiredSteps = 10_000, currentSteps = 10_000).progress shouldBe 1.0
		}
	}

	@Nested
	@DisplayName("ActiveTimeChallengeInstance progress")
	inner class ActiveTimeChallengeTests {

		private fun createInstance(
			requiredMinutes: Int = 60,
			currentMinutes: Int = 0
		): ActiveTimeChallengeInstance {
			val entry = createEntry(type = ChallengeType.ActiveTime)
			val entity = ActiveTimeChallengeEntity(entry.id, false, currentMinutes, requiredMinutes)
			return ActiveTimeChallengeInstance(entry, mockk(relaxed = true), entity)
		}

		@Test
		fun `progress is zero at start`() {
			createInstance(requiredMinutes = 60, currentMinutes = 0).progress shouldBe 0.0
		}

		@Test
		fun `progress is 1_0 at exact completion`() {
			createInstance(requiredMinutes = 60, currentMinutes = 60).progress shouldBe 1.0
		}

		@Test
		fun `progress reflects partial completion`() {
			createInstance(requiredMinutes = 120, currentMinutes = 30).progress shouldBe 0.25
		}
	}

	@Nested
	@DisplayName("ExplorerChallengeInstance progress")
	inner class ExplorerChallengeTests {

		private fun createInstance(
			requiredLocations: Int = 100,
			currentLocations: Int = 0
		): ExplorerChallengeInstance {
			val entry = createEntry(type = ChallengeType.Explorer)
			val entity = ExplorerChallengeEntity(entry.id, false, requiredLocations, currentLocations)
			return ExplorerChallengeInstance(entry, mockk(relaxed = true), entity)
		}

		@Test
		fun `progress is fraction of locations found`() {
			createInstance(requiredLocations = 100, currentLocations = 25).progress shouldBe 0.25
		}

		@Test
		fun `progress at full completion`() {
			createInstance(requiredLocations = 50, currentLocations = 50).progress shouldBe 1.0
		}
	}

	@Nested
	@DisplayName("Challenge entity mutation")
	inner class EntityMutationTests {

		@Test
		fun `step entity accumulates steps`() {
			val entity = StepChallengeEntity(1L, false, 10_000, 0)
			entity.stepCount += 500
			entity.stepCount += 300
			entity.stepCount shouldBe 800
		}

		@Test
		fun `walk distance entity accumulates distance`() {
			val entity = WalkDistanceChallengeEntity(1L, false, 5000f, 0f)
			entity.distanceInM += 1500f
			entity.distanceInM += 2000f
			entity.distanceInM shouldBe 3500f
		}

		@Test
		fun `active time entity accumulates minutes`() {
			val entity = ActiveTimeChallengeEntity(1L, false, 0, 60)
			entity.activeTimeInMinutes += 15
			entity.activeTimeInMinutes += 10
			entity.activeTimeInMinutes shouldBe 25
		}

		@Test
		fun `explorer entity accumulates locations`() {
			val entity = ExplorerChallengeEntity(1L, false, 100, 0)
			entity.locationCount += 10
			entity.locationCount += 5
			entity.locationCount shouldBe 15
		}

		@Test
		fun `entry extra isCompleted can be toggled`() {
			val entity = StepChallengeEntity(1L, false, 10_000, 10_000)
			entity.isCompleted shouldBe false
			entity.isCompleted = true
			entity.isCompleted shouldBe true
		}
	}
}
