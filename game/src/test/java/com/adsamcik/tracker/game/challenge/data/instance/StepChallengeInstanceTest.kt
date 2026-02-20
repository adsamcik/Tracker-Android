package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [StepChallengeInstance] covering progress tracking,
 * difficulty tiers, completion thresholds, and session processing.
 */
@DisplayName("StepChallengeInstance")
class StepChallengeInstanceTest {

	private fun createEntry(
		startTime: Long = 1_000_000L,
		endTime: Long = 2_000_000L,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM
	): ChallengeEntry = ChallengeEntry(ChallengeType.Step, startTime, endTime, difficulty).also { it.id = 1L }

	private fun createInstance(
		requiredSteps: Int = 10_000,
		currentSteps: Int = 0,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM
	): StepChallengeInstance {
		val entry = createEntry(difficulty = difficulty)
		val entity = StepChallengeEntity(entry.id, false, requiredSteps, currentSteps)
		return StepChallengeInstance(entry, mockk(relaxed = true), entity)
	}

	private fun createSession(
		steps: Int = 500,
		distanceOnFootInM: Float = 400f
	): TrackerSession = TrackerSession(
		id = 1L,
		start = 1_000L,
		end = 2_000L,
		isUserInitiated = true,
		collections = 1,
		distanceInM = distanceOnFootInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = 0f,
		steps = steps
	)

	@Nested
	@DisplayName("Progress tracking")
	inner class ProgressTracking {

		@Test
		fun `progress is zero when no steps taken`() {
			createInstance(requiredSteps = 10_000, currentSteps = 0).progress shouldBe 0.0
		}

		@Test
		fun `progress is half when half steps completed`() {
			createInstance(requiredSteps = 10_000, currentSteps = 5_000).progress shouldBe 0.5
		}

		@Test
		fun `progress is exactly one at completion`() {
			createInstance(requiredSteps = 10_000, currentSteps = 10_000).progress shouldBe 1.0
		}

		@Test
		fun `progress exceeds one when steps exceed requirement`() {
			createInstance(requiredSteps = 10_000, currentSteps = 15_000).progress shouldBeGreaterThan 1.0
		}

		@Test
		fun `progress at one quarter`() {
			createInstance(requiredSteps = 8_000, currentSteps = 2_000).progress shouldBe 0.25
		}

		@Test
		fun `progress with small required steps`() {
			createInstance(requiredSteps = 100, currentSteps = 75).progress shouldBe 0.75
		}

		@Test
		fun `progress with single step remaining`() {
			val instance = createInstance(requiredSteps = 1_000, currentSteps = 999)
			instance.progress shouldBeLessThan 1.0
			instance.progress shouldBeGreaterThan 0.99
		}
	}

	@Nested
	@DisplayName("Difficulty tiers")
	inner class DifficultyTiers {

		@Test
		fun `instance exposes VERY_EASY difficulty`() {
			createInstance(difficulty = ChallengeDifficulty.VERY_EASY).difficulty shouldBe ChallengeDifficulty.VERY_EASY
		}

		@Test
		fun `instance exposes EASY difficulty`() {
			createInstance(difficulty = ChallengeDifficulty.EASY).difficulty shouldBe ChallengeDifficulty.EASY
		}

		@Test
		fun `instance exposes MEDIUM difficulty`() {
			createInstance(difficulty = ChallengeDifficulty.MEDIUM).difficulty shouldBe ChallengeDifficulty.MEDIUM
		}

		@Test
		fun `instance exposes HARD difficulty`() {
			createInstance(difficulty = ChallengeDifficulty.HARD).difficulty shouldBe ChallengeDifficulty.HARD
		}

		@Test
		fun `instance exposes VERY_HARD difficulty`() {
			createInstance(difficulty = ChallengeDifficulty.VERY_HARD).difficulty shouldBe ChallengeDifficulty.VERY_HARD
		}
	}

	@Nested
	@DisplayName("Completion thresholds")
	inner class CompletionThresholds {

		@BeforeEach
		fun setUp() {
			mockkObject(Logger)
			mockkObject(ChallengeDatabase.Companion)
			every { Logger.log(any()) } returns Unit
			every { Logger.logWithPreference(any(), any(), any()) } returns Unit
			every { ChallengeDatabase.database(any()) } returns mockk(relaxed = true)
		}

		@AfterEach
		fun tearDown() {
			unmockkObject(Logger)
			unmockkObject(ChallengeDatabase.Companion)
		}

		@Test
		fun `not completed when steps below required`(){
			val instance = createInstance(requiredSteps = 10_000, currentSteps = 9_999)
			instance.extra.isCompleted shouldBe false
		}

		@Test
		fun `completion detection via process when steps meet threshold`() {
			val instance = createInstance(requiredSteps = 1_000, currentSteps = 900)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(steps = 100)) { completed = true }

			instance.extra.isCompleted shouldBe true
			completed shouldBe true
		}

		@Test
		fun `completion detection via process when steps exceed threshold`() {
			val instance = createInstance(requiredSteps = 1_000, currentSteps = 800)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(steps = 500)) { completed = true }

			instance.extra.isCompleted shouldBe true
			instance.extra.stepCount shouldBe 1_300
			completed shouldBe true
		}

		@Test
		fun `no completion when steps still below threshold`() {
			val instance = createInstance(requiredSteps = 10_000, currentSteps = 0)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(steps = 500)) { completed = true }

			instance.extra.isCompleted shouldBe false
			completed shouldBe false
		}

		@Test
		fun `zero required steps completes immediately`() {
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, false, 0, 0)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(steps = 0)) { completed = true }

			entity.isCompleted shouldBe true
			completed shouldBe true
		}
	}

	@Nested
	@DisplayName("Session processing")
	inner class SessionProcessing {

		@BeforeEach
		fun setUp() {
			mockkObject(Logger)
			mockkObject(ChallengeDatabase.Companion)
			every { Logger.log(any()) } returns Unit
			every { Logger.logWithPreference(any(), any(), any()) } returns Unit
			every { ChallengeDatabase.database(any()) } returns mockk(relaxed = true)
		}

		@AfterEach
		fun tearDown() {
			unmockkObject(Logger)
			unmockkObject(ChallengeDatabase.Companion)
		}

		@Test
		fun `processSession accumulates steps from session`(){
			val instance = createInstance(requiredSteps = 10_000, currentSteps = 0)
			val context = mockk<Context>(relaxed = true)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(steps = 1_000), listener)

			instance.extra.stepCount shouldBe 1_000
		}

		@Test
		fun `multiple sessions accumulate steps`() {
			val instance = createInstance(requiredSteps = 10_000, currentSteps = 0)
			val context = mockk<Context>(relaxed = true)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(steps = 1_000), listener)
			instance.process(context, createSession(steps = 2_000), listener)
			instance.process(context, createSession(steps = 3_000), listener)

			instance.extra.stepCount shouldBe 6_000
		}

		@Test
		fun `session with zero steps does not change progress`() {
			val instance = createInstance(requiredSteps = 10_000, currentSteps = 5_000)
			val context = mockk<Context>(relaxed = true)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(steps = 0), listener)

			instance.extra.stepCount shouldBe 5_000
			instance.progress shouldBe 0.5
		}

		@Test
		fun `completed challenge ignores further sessions`() {
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, true, 10_000, 10_000)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)
			val context = mockk<Context>(relaxed = true)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(steps = 5_000), listener)

			instance.extra.stepCount shouldBe 10_000
		}

		@Test
		fun `progress percentage matches accumulated steps`() {
			val instance = createInstance(requiredSteps = 4_000, currentSteps = 0)
			val context = mockk<Context>(relaxed = true)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(steps = 1_000), listener)
			instance.progress shouldBe 0.25

			instance.process(context, createSession(steps = 1_000), listener)
			instance.progress shouldBe 0.5

			instance.process(context, createSession(steps = 1_000), listener)
			instance.progress shouldBe 0.75
		}
	}
}
