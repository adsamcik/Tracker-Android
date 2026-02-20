package com.adsamcik.tracker.game.repository

import android.app.Application
import com.adsamcik.tracker.game.challenge.ChallengeManager
import com.adsamcik.tracker.game.challenge.data.ChallengeInstance
import com.adsamcik.tracker.game.challenge.database.data.ChallengeEntry
import com.adsamcik.tracker.game.goals.GoalTracker
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.points.database.PointsDatabase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DefaultGameRepository")
class DefaultGameRepositoryTest {

	private lateinit var application: Application
	private lateinit var pointsDao: PointsAwardedDao
	private lateinit var testScope: TestScope

	@BeforeEach
	fun setUp() {
		application = mockk(relaxed = true)
		pointsDao = mockk(relaxed = true)

		mockkObject(PointsDatabase)
		mockkObject(GoalTracker)
		mockkObject(ChallengeManager)

		val mockDb = mockk<PointsDatabase>(relaxed = true)
		every { PointsDatabase.database(any()) } returns mockDb
		every { mockDb.pointsAwardedDao() } returns pointsDao

		every { GoalTracker.initialize(any()) } returns Unit
		every { ChallengeManager.initialize(any(), any()) } returns Unit

		testScope = TestScope(UnconfinedTestDispatcher())
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	private fun createRepository(): DefaultGameRepository {
		return DefaultGameRepository(application, testScope)
	}

	@Nested
	@DisplayName("getPointsToday")
	inner class GetPointsToday {

		@Test
		fun `emits points from DAO`() = runTest {
			every { pointsDao.countBetweenFlow(any(), any()) } returns flowOf(42)

			val repo = createRepository()
			val result = repo.getPointsToday().first()

			result shouldBe 42
		}

		@Test
		fun `emits zero when no points today`() = runTest {
			every { pointsDao.countBetweenFlow(any(), any()) } returns flowOf(0)

			val repo = createRepository()
			val result = repo.getPointsToday().first()

			result shouldBe 0
		}
	}

	@Nested
	@DisplayName("getStepsSummary")
	inner class GetStepsSummary {

		@Test
		fun `initial value is null`() = runTest {
			every { GoalTracker.stepsDay } returns MutableStateFlow(0)
			every { GoalTracker.goalDay } returns MutableStateFlow(0)
			every { GoalTracker.stepsWeek } returns MutableStateFlow(0)
			every { GoalTracker.goalWeek } returns MutableStateFlow(0)

			val repo = createRepository()
			val flow = repo.getStepsSummary()

			// StateFlow with SharingStarted.Lazily has null initial
			flow.value.shouldBeNull()
		}

		@Test
		fun `combines step and goal data into summary`() = runTest {
			val stepsDay = MutableStateFlow(500)
			val goalDay = MutableStateFlow(10_000)
			val stepsWeek = MutableStateFlow(3000)
			val goalWeek = MutableStateFlow(50_000)

			every { GoalTracker.stepsDay } returns stepsDay
			every { GoalTracker.goalDay } returns goalDay
			every { GoalTracker.stepsWeek } returns stepsWeek
			every { GoalTracker.goalWeek } returns goalWeek

			val repo = createRepository()
			val flow = repo.getStepsSummary()

			// Trigger collection by reading first emitted value
			val result = flow.first { it != null }

			result shouldBe StepsSummaryData(
				stepsToday = 500,
				stepsWeek = 3000,
				goalDay = 10_000,
				goalWeek = 50_000
			)
		}
	}

	@Nested
	@DisplayName("getActiveChallenges")
	inner class GetActiveChallenges {

		@Test
		fun `initial value is empty list`() = runTest {
			every { ChallengeManager.activeChallenges } returns MutableStateFlow(emptyList())

			val repo = createRepository()
			val flow = repo.getActiveChallenges()

			flow.value.shouldBeEmpty()
		}

		@Test
		fun `maps challenge instances to ChallengeData`() = runTest {
			val mockInstance = mockk<ChallengeInstance<*, *>>(relaxed = true) {
				every { data } returns ChallengeEntry(
					type = com.adsamcik.tracker.game.challenge.data.ChallengeType.Step,
					startTime = 1000L,
					endTime = 2000L,
					difficulty = com.adsamcik.tracker.game.challenge.ChallengeDifficulty.MEDIUM
				).apply { id = 42L }
				every { getTitle(any()) } returns "Walk Challenge"
				every { getDescription(any()) } returns "Walk 5km"
				every { progress } returns 0.75
			}

			every { ChallengeManager.activeChallenges } returns MutableStateFlow(listOf(mockInstance))

			val repo = createRepository()
			val flow = repo.getActiveChallenges()
			val result = flow.first { it.isNotEmpty() }

			result.size shouldBe 1
			result[0].id shouldBe 42L
			result[0].title shouldBe "Walk Challenge"
			result[0].description shouldBe "Walk 5km"
			result[0].progress shouldBe 0.75f
		}
	}

	@Nested
	@DisplayName("initialization")
	inner class Initialization {

		@Test
		fun `initializes GoalTracker and ChallengeManager`() {
			createRepository()

			io.mockk.verify { GoalTracker.initialize(application) }
			io.mockk.verify { ChallengeManager.initialize(application) }
		}
	}

	@Nested
	@DisplayName("startOfDay")
	inner class StartOfDay {

		@Test
		fun `points query uses start of day`() = runTest {
			val capturedFrom = mutableListOf<Long>()
			every { pointsDao.countBetweenFlow(capture(capturedFrom), any()) } returns flowOf(0)

			val repo = createRepository()
			repo.getPointsToday().first()

			// Start of day should be aligned to midnight (divisible by 86_400_000)
			val from = capturedFrom.first()
			(from % 86_400_000L) shouldBe 0L
		}
	}
}
