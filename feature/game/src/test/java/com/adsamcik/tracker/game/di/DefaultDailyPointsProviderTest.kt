package com.adsamcik.tracker.game.di

import com.adsamcik.tracker.game.repository.GameRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
@DisplayName("DefaultDailyPointsProvider")
class DefaultDailyPointsProviderTest {

	private lateinit var gameRepository: GameRepository
	private lateinit var testScope: TestScope

	@BeforeEach
	fun setUp() {
		gameRepository = mockk(relaxed = true)
		testScope = TestScope(UnconfinedTestDispatcher())
	}

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Nested
	@DisplayName("Initial state")
	inner class InitialState {

		@Test
		fun `initial value is zero`() {
			every { gameRepository.getPointsToday() } returns flowOf()

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)

			provider.pointsTodayFlow.value shouldBe 0
		}
	}

	@Nested
	@DisplayName("Point updates")
	inner class PointUpdates {

		@Test
		fun `emits points from repository`() = runTest {
			every { gameRepository.getPointsToday() } returns flowOf(100)

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)
			val result = provider.pointsTodayFlow.first { it > 0 }

			result shouldBe 100
		}

		@Test
		fun `emits zero when repository returns zero`() = runTest {
			every { gameRepository.getPointsToday() } returns flowOf(0)

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)
			val result = provider.pointsTodayFlow.first()

			result shouldBe 0
		}

		@Test
		fun `updates when upstream flow emits new values`() = runTest {
			val pointsFlow = MutableSharedFlow<Int>(replay = 1)
			every { gameRepository.getPointsToday() } returns pointsFlow

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)

			// Initial value should be 0
			provider.pointsTodayFlow.value shouldBe 0

			// Emit a new value
			pointsFlow.emit(50)
			val result = provider.pointsTodayFlow.first { it > 0 }

			result shouldBe 50
		}

		@Test
		fun `tracks latest emitted value`() = runTest {
			val pointsFlow = MutableSharedFlow<Int>(replay = 1)
			every { gameRepository.getPointsToday() } returns pointsFlow

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)

			pointsFlow.emit(10)
			provider.pointsTodayFlow.first { it == 10 } shouldBe 10

			pointsFlow.emit(25)
			provider.pointsTodayFlow.first { it == 25 } shouldBe 25
		}
	}

	@Nested
	@DisplayName("Large values")
	inner class LargeValues {

		@Test
		fun `handles large point values`() = runTest {
			every { gameRepository.getPointsToday() } returns flowOf(999_999)

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)
			val result = provider.pointsTodayFlow.first { it > 0 }

			result shouldBe 999_999
		}
	}

	@Nested
	@DisplayName("Point accumulation")
	inner class PointAccumulation {

		@Test
		fun `reflects cumulative points from repository`() = runTest {
			val pointsFlow = MutableSharedFlow<Int>(replay = 1)
			every { gameRepository.getPointsToday() } returns pointsFlow

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)

			// Simulate accumulation (repository emits cumulative totals)
			pointsFlow.emit(10)
			provider.pointsTodayFlow.first { it == 10 } shouldBe 10

			pointsFlow.emit(30)
			provider.pointsTodayFlow.first { it == 30 } shouldBe 30

			pointsFlow.emit(55)
			provider.pointsTodayFlow.first { it == 55 } shouldBe 55
		}
	}

	@Nested
	@DisplayName("Reset behavior")
	inner class ResetBehavior {

		@Test
		fun `reflects reset when repository emits zero`() = runTest {
			val pointsFlow = MutableSharedFlow<Int>(replay = 1)
			every { gameRepository.getPointsToday() } returns pointsFlow

			val provider = DefaultDailyPointsProvider(gameRepository, testScope)

			pointsFlow.emit(100)
			provider.pointsTodayFlow.first { it == 100 } shouldBe 100

			// Simulate day reset: repository emits 0
			pointsFlow.emit(0)
			provider.pointsTodayFlow.first { it == 0 } shouldBe 0
		}
	}
}
