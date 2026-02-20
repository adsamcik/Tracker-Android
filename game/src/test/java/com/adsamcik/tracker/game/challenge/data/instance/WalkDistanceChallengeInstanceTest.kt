package com.adsamcik.tracker.game.challenge.data.instance

import android.content.Context
import com.adsamcik.tracker.game.challenge.ChallengeDifficulty
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.entity.WalkDistanceChallengeEntity
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
 * Tests for [WalkDistanceChallengeInstance] covering distance-based
 * progress, completion detection, and session processing.
 */
@DisplayName("WalkDistanceChallengeInstance")
class WalkDistanceChallengeInstanceTest {

	private fun createEntry(
		startTime: Long = 1_000_000L,
		endTime: Long = 2_000_000L,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM
	): ChallengeEntry = ChallengeEntry(ChallengeType.WalkDistance, startTime, endTime, difficulty).also { it.id = 1L }

	private fun createInstance(
		requiredDistanceInM: Float = 10_000f,
		currentDistanceInM: Float = 0f,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM
	): WalkDistanceChallengeInstance {
		val entry = createEntry(difficulty = difficulty)
		val entity = WalkDistanceChallengeEntity(entry.id, false, requiredDistanceInM, currentDistanceInM)
		return WalkDistanceChallengeInstance(entry, mockk(relaxed = true), entity)
	}

	private fun createSession(
		distanceOnFootInM: Float = 500f,
		steps: Int = 700,
		start: Long = 1_000L,
		end: Long = 2_000L
	): TrackerSession = TrackerSession(
		id = 1L,
		start = start,
		end = end,
		isUserInitiated = true,
		collections = 1,
		distanceInM = distanceOnFootInM + 100f,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = 100f,
		steps = steps
	)

	@Nested
	@DisplayName("Distance progress tracking")
	inner class DistanceProgressTracking {

		@Test
		fun `progress is zero when no distance covered`() {
			createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 0f).progress shouldBe 0.0
		}

		@Test
		fun `progress is half when half distance covered`() {
			createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 5_000f).progress shouldBe 0.5
		}

		@Test
		fun `progress is exactly one at completion`() {
			createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 10_000f).progress shouldBe 1.0
		}

		@Test
		fun `progress exceeds one when distance exceeds requirement`() {
			createInstance(requiredDistanceInM = 5_000f, currentDistanceInM = 7_500f).progress shouldBeGreaterThan 1.0
		}

		@Test
		fun `progress at one quarter`() {
			createInstance(requiredDistanceInM = 8_000f, currentDistanceInM = 2_000f).progress shouldBe 0.25
		}

		@Test
		fun `progress with small distance requirement`() {
			createInstance(requiredDistanceInM = 100f, currentDistanceInM = 75f).progress shouldBe 0.75
		}

		@Test
		fun `progress with fractional meters`() {
			val instance = createInstance(requiredDistanceInM = 1_000f, currentDistanceInM = 333.33f)
			instance.progress shouldBeGreaterThan 0.33
			instance.progress shouldBeLessThan 0.34
		}
	}

	@Nested
	@DisplayName("Unit conversion and distance accumulation")
	inner class UnitConversion {

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
		fun `distance accumulates from distanceOnFootInM in session`(){
			val instance = createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 0f)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(distanceOnFootInM = 1_500f), listener)

			instance.extra.distanceInM shouldBe 1_500f
		}

		@Test
		fun `distance uses on-foot distance not total distance`() {
			val instance = createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 0f)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			// Session has 500m on foot, but 600m total (100m in vehicle)
			instance.process(context, createSession(distanceOnFootInM = 500f), listener)

			instance.extra.distanceInM shouldBe 500f
		}

		@Test
		fun `multiple sessions accumulate distance`() {
			val instance = createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 0f)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(distanceOnFootInM = 1_000f), listener)
			instance.process(context, createSession(distanceOnFootInM = 2_500f), listener)
			instance.process(context, createSession(distanceOnFootInM = 1_500f), listener)

			instance.extra.distanceInM shouldBe 5_000f
		}

		@Test
		fun `zero distance session does not change progress`() {
			val instance = createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 3_000f)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(distanceOnFootInM = 0f), listener)

			instance.extra.distanceInM shouldBe 3_000f
			instance.progress shouldBe 0.3
		}

		@Test
		fun `large distance values accumulate correctly`() {
			val instance = createInstance(requiredDistanceInM = 100_000f, currentDistanceInM = 50_000f)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(distanceOnFootInM = 25_000f), listener)

			instance.extra.distanceInM shouldBe 75_000f
			instance.progress shouldBe 0.75
		}
	}

	@Nested
	@DisplayName("Completion detection")
	inner class CompletionDetection {

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
		fun `not completed when distance below required`(){
			val instance = createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 9_999f)
			instance.extra.isCompleted shouldBe false
		}

		@Test
		fun `completion via process when distance meets threshold`() {
			val instance = createInstance(requiredDistanceInM = 5_000f, currentDistanceInM = 4_500f)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(distanceOnFootInM = 500f)) { completed = true }

			instance.extra.isCompleted shouldBe true
			completed shouldBe true
		}

		@Test
		fun `completion via process when distance exceeds threshold`() {
			val instance = createInstance(requiredDistanceInM = 5_000f, currentDistanceInM = 4_000f)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(distanceOnFootInM = 2_000f)) { completed = true }

			instance.extra.isCompleted shouldBe true
			instance.extra.distanceInM shouldBe 6_000f
			completed shouldBe true
		}

		@Test
		fun `no completion when distance still below threshold`() {
			val instance = createInstance(requiredDistanceInM = 10_000f, currentDistanceInM = 0f)
			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(distanceOnFootInM = 500f)) { completed = true }

			instance.extra.isCompleted shouldBe false
			completed shouldBe false
		}

		@Test
		fun `completed challenge ignores further sessions`() {
			val entry = createEntry()
			val entity = WalkDistanceChallengeEntity(entry.id, true, 5_000f, 5_000f)
			val instance = WalkDistanceChallengeInstance(entry, mockk(relaxed = true), entity)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(distanceOnFootInM = 2_000f), listener)

			instance.extra.distanceInM shouldBe 5_000f
		}

		@Test
		fun `progress percentage tracks correctly through completion`() {
			val instance = createInstance(requiredDistanceInM = 4_000f, currentDistanceInM = 0f)
			val context = mockk<Context>(relaxed = true)
			val listener: (WalkDistanceChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(distanceOnFootInM = 1_000f), listener)
			instance.progress shouldBe 0.25

			instance.process(context, createSession(distanceOnFootInM = 1_000f), listener)
			instance.progress shouldBe 0.5

			instance.process(context, createSession(distanceOnFootInM = 1_000f), listener)
			instance.progress shouldBe 0.75
		}
	}

	@Nested
	@DisplayName("Challenge metadata")
	inner class ChallengeMetadata {

		@Test
		fun `entry type is WalkDistance`() {
			val instance = createInstance()
			instance.data.type shouldBe ChallengeType.WalkDistance
		}

		@Test
		fun `difficulty is exposed from entry`() {
			val instance = createInstance(difficulty = ChallengeDifficulty.HARD)
			instance.difficulty shouldBe ChallengeDifficulty.HARD
		}

		@Test
		fun `duration is endTime minus startTime`() {
			val entry = createEntry(startTime = 100_000L, endTime = 300_000L)
			val entity = WalkDistanceChallengeEntity(entry.id, false, 5_000f, 0f)
			val instance = WalkDistanceChallengeInstance(entry, mockk(relaxed = true), entity)

			instance.duration shouldBe 200_000L
		}

		@Test
		fun `startTime and endTime are exposed correctly`() {
			val entry = createEntry(startTime = 500L, endTime = 1_500L)
			val entity = WalkDistanceChallengeEntity(entry.id, false, 5_000f, 0f)
			val instance = WalkDistanceChallengeInstance(entry, mockk(relaxed = true), entity)

			instance.startTime shouldBe 500L
			instance.endTime shouldBe 1_500L
		}
	}
}
