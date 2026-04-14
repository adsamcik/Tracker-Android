package com.adsamcik.tracker.game.ui.compose

import app.cash.turbine.test
import com.adsamcik.tracker.game.leaderboard.GhostLeaderboardProvider
import com.adsamcik.tracker.game.leaderboard.LeaderboardMetric
import com.adsamcik.tracker.game.leaderboard.LeaderboardState
import com.adsamcik.tracker.game.minigame.MiniGame
import com.adsamcik.tracker.game.minigame.MiniGameRegistry
import com.adsamcik.tracker.game.minigame.MiniGameSession
import com.adsamcik.tracker.game.repository.ChallengeData
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StepsSummaryData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("GameViewModel")
class GameViewModelTest {

	private val testDispatcher = StandardTestDispatcher()

	private lateinit var gameRepository: GameRepository
	private lateinit var miniGameRegistry: MiniGameRegistry
	private lateinit var ghostLeaderboardProvider: GhostLeaderboardProvider

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		gameRepository = mockk(relaxed = true)
		miniGameRegistry = mockk(relaxed = true)
		ghostLeaderboardProvider = mockk(relaxed = true)

		// Default stubs
		every { gameRepository.getPointsToday() } returns flowOf(0)
		every { gameRepository.getStepsSummary() } returns MutableStateFlow(null)
		every { gameRepository.getActiveChallenges() } returns MutableStateFlow(emptyList())
		every { gameRepository.getPlayerProfile() } returns flowOf(null)
		every { miniGameRegistry.allSorted() } returns emptyList()
		coEvery { ghostLeaderboardProvider.getLeaderboard(any(), any(), any()) } returns mockk(relaxed = true)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel() = GameViewModel(
		gameRepository = gameRepository,
		miniGameRegistry = miniGameRegistry,
		ghostLeaderboardProvider = ghostLeaderboardProvider,
	)

	@Nested
	@DisplayName("pointsToday")
	inner class PointsToday {

		@Test
		fun `initial value is null`() = runTest {
			val vm = createViewModel()
			vm.pointsToday.value.shouldBeNull()
		}

		@Test
		fun `emits repository value`() = runTest {
			every { gameRepository.getPointsToday() } returns flowOf(42)
			val vm = createViewModel()

			vm.pointsToday.test {
				skipItems(1) // skip initial null
				awaitItem() shouldBe 42
			}
		}
	}

	@Nested
	@DisplayName("stepsSummary")
	inner class StepsSummary {

		@Test
		fun `maps StepsSummaryData to StepsSummaryUi`() = runTest {
			val data = StepsSummaryData(
				stepsToday = 5000,
				stepsWeek = 30000,
				goalDay = 10000,
				goalWeek = 50000,
			)
			every { gameRepository.getStepsSummary() } returns MutableStateFlow(data)
			val vm = createViewModel()

			vm.stepsSummary.test {
				skipItems(1) // skip initial null
				val ui = awaitItem()
				ui.shouldNotBeNull()
				ui.stepsToday shouldBe 5000
				ui.stepsWeek shouldBe 30000
				ui.goalDay shouldBe 10000
				ui.goalWeek shouldBe 50000
			}
		}

		@Test
		fun `null data maps to null`() = runTest {
			every { gameRepository.getStepsSummary() } returns MutableStateFlow(null)
			val vm = createViewModel()

			vm.stepsSummary.value.shouldBeNull()
		}
	}

	@Nested
	@DisplayName("challenges")
	inner class Challenges {

		@Test
		fun `maps ChallengeData list to ChallengeUi list`() = runTest {
			val dataList = listOf(
				ChallengeData(
					id = 1L,
					title = "Walk 5km",
					description = "Walk five kilometers",
					progress = 0.5f,
					difficulty = "MEDIUM",
					timeRemainingMs = 3600_000L,
				),
				ChallengeData(
					id = 2L,
					title = "10k Steps",
					description = "Take ten thousand steps",
					progress = 0.8f,
					difficulty = "EASY",
					timeRemainingMs = 7200_000L,
				),
			)
			every { gameRepository.getActiveChallenges() } returns MutableStateFlow(dataList)
			val vm = createViewModel()

			vm.challenges.test {
				skipItems(1) // skip initial null
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 2
				result[0].id shouldBe 1L
				result[0].title shouldBe "Walk 5km"
				result[0].progress shouldBe 0.5f
				result[1].id shouldBe 2L
				result[1].difficulty shouldBe "EASY"
			}
		}
	}

	@Nested
	@DisplayName("miniGameEntries")
	inner class MiniGameEntries {

		private fun makeMiniGame(id: String, unlockLevel: Int): MiniGame = object : MiniGame {
			override val id = id
			override val nameRes = 0
			override val descriptionRes = 0
			override val unlockLevel = unlockLevel
			override fun createSession(): MiniGameSession = mockk()
		}

		@Test
		fun `games locked when player level is below unlockLevel`() = runTest {
			val profile = PlayerProfileUi(level = 2, totalXp = 100, xpIntoCurrentLevel = 50, xpForNextLevel = 200)
			every { gameRepository.getPlayerProfile() } returns flowOf(profile)
			every { miniGameRegistry.allSorted() } returns listOf(
				makeMiniGame("game_a", unlockLevel = 1),
				makeMiniGame("game_b", unlockLevel = 3),
				makeMiniGame("game_c", unlockLevel = 5),
			)
			val vm = createViewModel()

			vm.miniGameEntries.test {
				skipItems(1)
				val entries = awaitItem()
				entries.shouldNotBeNull()
				entries shouldHaveSize 3
				entries[0].isUnlocked shouldBe true   // level 1 <= 2
				entries[1].isUnlocked shouldBe false  // level 3 > 2
				entries[2].isUnlocked shouldBe false  // level 5 > 2
			}
		}

		@Test
		fun `null profile defaults to level 1`() = runTest {
			every { gameRepository.getPlayerProfile() } returns flowOf(null)
			every { miniGameRegistry.allSorted() } returns listOf(
				makeMiniGame("game_a", unlockLevel = 1),
				makeMiniGame("game_b", unlockLevel = 2),
			)
			val vm = createViewModel()

			vm.miniGameEntries.test {
				skipItems(1)
				val entries = awaitItem()
				entries.shouldNotBeNull()
				entries[0].isUnlocked shouldBe true  // level 1 <= 1
				entries[1].isUnlocked shouldBe false // level 2 > 1
			}
		}

		@Test
		fun `exact level match unlocks game`() = runTest {
			val profile = PlayerProfileUi(level = 5, totalXp = 500, xpIntoCurrentLevel = 0, xpForNextLevel = 300)
			every { gameRepository.getPlayerProfile() } returns flowOf(profile)
			every { miniGameRegistry.allSorted() } returns listOf(
				makeMiniGame("game_a", unlockLevel = 5),
			)
			val vm = createViewModel()

			vm.miniGameEntries.test {
				skipItems(1)
				val entries = awaitItem()
				entries.shouldNotBeNull()
				entries[0].isUnlocked shouldBe true
			}
		}
	}

	@Nested
	@DisplayName("leaderboard")
	inner class Leaderboard {

		@Test
		fun `selectLeaderboardMetric switches metric and reloads`() = runTest {
			val distanceState = LeaderboardState(
				metric = LeaderboardMetric.DISTANCE,
				currentWeekValue = 10.0,
				competitors = emptyList(),
				currentRank = 1,
				weekProgressFraction = 0.5f,
			)
			val stepsState = LeaderboardState(
				metric = LeaderboardMetric.STEPS,
				currentWeekValue = 5000.0,
				competitors = emptyList(),
				currentRank = 1,
				weekProgressFraction = 0.5f,
			)
			coEvery {
				ghostLeaderboardProvider.getLeaderboard(LeaderboardMetric.DISTANCE, any(), any())
			} returns distanceState
			coEvery {
				ghostLeaderboardProvider.getLeaderboard(LeaderboardMetric.STEPS, any(), any())
			} returns stepsState

			val vm = createViewModel()
			testDispatcher.scheduler.advanceUntilIdle()
			vm.leaderboardState.value?.metric shouldBe LeaderboardMetric.DISTANCE

			vm.selectLeaderboardMetric(LeaderboardMetric.STEPS)
			testDispatcher.scheduler.advanceUntilIdle()
			vm.leaderboardState.value?.metric shouldBe LeaderboardMetric.STEPS
		}

		@Test
		fun `selecting same metric does not reload`() = runTest {
			var callCount = 0
			coEvery {
				ghostLeaderboardProvider.getLeaderboard(any(), any(), any())
			} answers {
				callCount++
				mockk(relaxed = true)
			}

			val vm = createViewModel()
			testDispatcher.scheduler.advanceUntilIdle()
			val initialCount = callCount

			vm.selectLeaderboardMetric(LeaderboardMetric.DISTANCE) // same as default
			testDispatcher.scheduler.advanceUntilIdle()
			callCount shouldBe initialCount
		}
	}
}
