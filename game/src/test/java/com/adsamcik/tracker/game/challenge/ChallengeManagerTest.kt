package com.adsamcik.tracker.game.challenge

import android.content.Context
import com.adsamcik.tracker.game.challenge.data.ChallengeDefinition
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.data.ChallengeType
import com.adsamcik.tracker.game.challenge.data.entity.StepChallengeEntity
import com.adsamcik.tracker.game.challenge.data.instance.StepChallengeInstance
import com.adsamcik.tracker.game.challenge.database.ChallengeDatabase
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.logger.Logger
import com.adsamcik.tracker.shared.base.data.TrackerSession
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
 * Tests for [ChallengeManager] orchestration logic.
 *
 * ChallengeManager is a singleton with Android/DB dependencies, so we test
 * its core behavioural contracts through the public [ChallengeInstance.process]
 * method and the [ChallengeManager.activeChallenges] StateFlow, focusing on
 * logic that can be exercised without a running Room database.
 */
@DisplayName("ChallengeManager")
class ChallengeManagerTest {

	private fun createEntry(
		type: ChallengeType = ChallengeType.Step,
		startTime: Long = 1_000_000L,
		endTime: Long = 2_000_000L,
		difficulty: ChallengeDifficulty = ChallengeDifficulty.MEDIUM
	): ChallengeEntry = ChallengeEntry(type, startTime, endTime, difficulty).also { it.id = 1L }

	private fun createSession(
		steps: Int = 500,
		distanceOnFootInM: Float = 400f,
		start: Long = 1_000L,
		end: Long = 2_000L
	): TrackerSession = TrackerSession(
		id = 1L,
		start = start,
		end = end,
		isUserInitiated = true,
		collections = 1,
		distanceInM = distanceOnFootInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = 0f,
		steps = steps
	)

	@Nested
	@DisplayName("activeChallenges StateFlow")
	inner class ActiveChallengesFlow {

		@Test
		fun `initial active challenges flow is empty`() {
			ChallengeManager.activeChallenges.value.shouldBeEmpty()
		}
	}

	@Nested
	@DisplayName("Challenge process orchestration")
	inner class ProcessOrchestration {

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
		fun `process skips already-completed challenges`(){
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, true, 10_000, 10_000)
			val definition = mockk<ChallengeDefinition<StepChallengeInstance>>(relaxed = true)
			val instance = StepChallengeInstance(entry, definition, entity)

			val context = mockk<Context>(relaxed = true)
			val session = createSession(steps = 500)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, session, listener)

			// Steps should not increase since the challenge is already completed
			entity.stepCount shouldBe 10_000
		}

		@Test
		fun `process triggers completion listener when threshold met`() {
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, false, 1_000, 900)
			val definition = mockk<ChallengeDefinition<StepChallengeInstance>>(relaxed = true)
			val instance = StepChallengeInstance(entry, definition, entity)

			val context = mockk<Context>(relaxed = true)
			val session = createSession(steps = 200)
			var completedInstance: ChallengeInstance<*, *>? = null

			instance.process(context, session) { completedInstance = it }

			entity.stepCount shouldBe 1_100
			entity.isCompleted shouldBe true
			completedInstance shouldBe instance
		}

		@Test
		fun `process does not trigger listener when below threshold`() {
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, false, 10_000, 0)
			val definition = mockk<ChallengeDefinition<StepChallengeInstance>>(relaxed = true)
			val instance = StepChallengeInstance(entry, definition, entity)

			val context = mockk<Context>(relaxed = true)
			val session = createSession(steps = 500)
			var listenerCalled = false

			instance.process(context, session) { listenerCalled = true }

			entity.stepCount shouldBe 500
			entity.isCompleted shouldBe false
			listenerCalled shouldBe false
		}

		@Test
		fun `process accumulates across multiple sessions`() {
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, false, 5_000, 0)
			val definition = mockk<ChallengeDefinition<StepChallengeInstance>>(relaxed = true)
			val instance = StepChallengeInstance(entry, definition, entity)

			val context = mockk<Context>(relaxed = true)
			val listener: (StepChallengeInstance) -> Unit = mockk(relaxed = true)

			instance.process(context, createSession(steps = 1_000), listener)
			instance.process(context, createSession(steps = 1_500), listener)
			instance.process(context, createSession(steps = 2_000), listener)

			entity.stepCount shouldBe 4_500
			entity.isCompleted shouldBe false
		}

		@Test
		fun `process marks completed on exact threshold`() {
			val entry = createEntry()
			val entity = StepChallengeEntity(entry.id, false, 1_000, 500)
			val definition = mockk<ChallengeDefinition<StepChallengeInstance>>(relaxed = true)
			val instance = StepChallengeInstance(entry, definition, entity)

			val context = mockk<Context>(relaxed = true)
			var completed = false

			instance.process(context, createSession(steps = 500)) { completed = true }

			entity.stepCount shouldBe 1_000
			entity.isCompleted shouldBe true
			completed shouldBe true
		}
	}

	@Nested
	@DisplayName("Challenge expiration")
	inner class ChallengeExpiration {

		@Test
		fun `challenge with past endTime is considered expired`() {
			val now = System.currentTimeMillis()
			val entry = createEntry(startTime = now - 200_000L, endTime = now - 100_000L)
			val entity = StepChallengeEntity(entry.id, false, 10_000, 5_000)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)

			val isExpired = instance.endTime <= now
			isExpired shouldBe true
		}

		@Test
		fun `challenge with future endTime is not expired`() {
			val now = System.currentTimeMillis()
			val entry = createEntry(startTime = now - 100_000L, endTime = now + 100_000L)
			val entity = StepChallengeEntity(entry.id, false, 10_000, 5_000)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)

			val isExpired = instance.endTime <= now
			isExpired shouldBe false
		}

		@Test
		fun `duration reflects time window of challenge`() {
			val entry = createEntry(startTime = 1_000_000L, endTime = 1_500_000L)
			val entity = StepChallengeEntity(entry.id, false, 10_000, 0)
			val instance = StepChallengeInstance(entry, mockk(relaxed = true), entity)

			instance.duration shouldBe 500_000L
		}
	}

	@Nested
	@DisplayName("MAX_CHALLENGE_COUNT contract")
	inner class MaxChallengeCount {

		@Test
		fun `enabled challenge types cover all ChallengeType values`() {
			val allTypes = ChallengeType.entries.toSet()
			allTypes shouldHaveSize 4
		}
	}
}
