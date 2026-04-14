package com.adsamcik.tracker.game.ui.compose

import app.cash.turbine.test
import com.adsamcik.tracker.game.challenge.progression.UnlockableFeature
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.PlayerProfileUi
import com.adsamcik.tracker.game.repository.StreakUi
import com.adsamcik.tracker.game.repository.TrophySummaryUi
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
@DisplayName("ProgressionViewModel")
class ProgressionViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private lateinit var gameRepository: GameRepository
	private val profileFlow = MutableSharedFlow<PlayerProfileUi?>(replay = 1)

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		gameRepository = mockk(relaxed = true)

		every { gameRepository.getPlayerProfile() } returns profileFlow
		every { gameRepository.getStreak() } returns flowOf(null)
		every { gameRepository.getTrophySummary() } returns flowOf(
			TrophySummaryUi(totalCompleted = 0, goldCount = 0, silverCount = 0, bronzeCount = 0),
		)
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel() = ProgressionViewModel(gameRepository = gameRepository)

	private fun makeProfile(level: Int) = PlayerProfileUi(
		level = level,
		totalXp = level * 1000L,
		xpIntoCurrentLevel = 0,
		xpForNextLevel = 1000,
	)

	@Nested
	@DisplayName("heroLevelState")
	inner class HeroLevelState {

		@Test
		fun `initial value is null`() = runTest {
			val vm = createViewModel()
			vm.heroLevelState.value.shouldBeNull()
		}

		@Test
		fun `combines profile and streak`() = runTest {
			val streak = StreakUi(currentCount = 5, bestCount = 10, freezeCount = 2)
			every { gameRepository.getStreak() } returns flowOf(streak)
			every { gameRepository.getPlayerProfile() } returns flowOf(makeProfile(3))

			val vm = createViewModel()

			vm.heroLevelState.test {
				skipItems(1) // skip null
				val state = awaitItem()
				state.shouldNotBeNull()
				state.playerProfile?.level shouldBe 3
				state.streak?.currentCount shouldBe 5
				state.streak?.bestCount shouldBe 10
			}
		}
	}

	@Nested
	@DisplayName("unlockEvents")
	inner class UnlockEvents {

		@Test
		fun `no event emitted on first profile emission`() = runTest {
			val vm = createViewModel()
			vm.unlockEvents.test {
				profileFlow.emit(makeProfile(3))
				testDispatcher.scheduler.advanceUntilIdle()
				expectNoEvents()
			}
		}

		@Test
		fun `level-up emits UnlockEvent with correct level`() = runTest {
			val vm = createViewModel()
			vm.unlockEvents.test {
				profileFlow.emit(makeProfile(1))
				testDispatcher.scheduler.advanceUntilIdle()

				profileFlow.emit(makeProfile(2))
				testDispatcher.scheduler.advanceUntilIdle()

				val event = awaitItem()
				event.newLevel shouldBe 2
			}
		}

		@Test
		fun `level-up from 1 to 2 unlocks PERSONAL_RECORDS`() = runTest {
			val vm = createViewModel()
			vm.unlockEvents.test {
				profileFlow.emit(makeProfile(1))
				testDispatcher.scheduler.advanceUntilIdle()

				profileFlow.emit(makeProfile(2))
				testDispatcher.scheduler.advanceUntilIdle()

				val event = awaitItem()
				event.unlockedFeatures shouldContainExactly listOf(UnlockableFeature.PERSONAL_RECORDS)
			}
		}

		@Test
		fun `multi-level jump includes all features in range`() = runTest {
			val vm = createViewModel()
			vm.unlockEvents.test {
				profileFlow.emit(makeProfile(1))
				testDispatcher.scheduler.advanceUntilIdle()

				// Jump from level 1 to level 5
				profileFlow.emit(makeProfile(5))
				testDispatcher.scheduler.advanceUntilIdle()

				val event = awaitItem()
				event.newLevel shouldBe 5
				// Levels 2-5 features: PERSONAL_RECORDS(2), OUTRUN_MINI_GAME(3), STREAK_FREEZE(4), SPEED_CHALLENGE(5)
				val expectedFeatures = UnlockableFeature.entries.filter { it.requiredLevel in 2..5 }
				event.unlockedFeatures shouldContainExactly expectedFeatures
			}
		}

		@Test
		fun `same level emitted twice does not trigger event`() = runTest {
			val vm = createViewModel()
			vm.unlockEvents.test {
				profileFlow.emit(makeProfile(3))
				testDispatcher.scheduler.advanceUntilIdle()

				profileFlow.emit(makeProfile(3))
				testDispatcher.scheduler.advanceUntilIdle()

				expectNoEvents()
			}
		}

		@Test
		fun `level decrease does not trigger event`() = runTest {
			val vm = createViewModel()
			vm.unlockEvents.test {
				profileFlow.emit(makeProfile(5))
				testDispatcher.scheduler.advanceUntilIdle()

				profileFlow.emit(makeProfile(3))
				testDispatcher.scheduler.advanceUntilIdle()

				expectNoEvents()
			}
		}
	}

	@Nested
	@DisplayName("trophySummary")
	inner class TrophySummaryTest {

		@Test
		fun `emits repository trophy summary`() = runTest {
			val summary = TrophySummaryUi(totalCompleted = 10, goldCount = 3, silverCount = 4, bronzeCount = 3)
			every { gameRepository.getTrophySummary() } returns flowOf(summary)

			val vm = createViewModel()

			vm.trophySummary.test {
				skipItems(1) // null initial
				val result = awaitItem()
				result.shouldNotBeNull()
				result.totalCompleted shouldBe 10
				result.goldCount shouldBe 3
			}
		}
	}
}
