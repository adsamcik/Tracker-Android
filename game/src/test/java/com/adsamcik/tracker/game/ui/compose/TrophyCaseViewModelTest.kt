package com.adsamcik.tracker.game.ui.compose

import app.cash.turbine.test
import com.adsamcik.tracker.game.repository.ChallengeData
import com.adsamcik.tracker.game.repository.GameRepository
import com.adsamcik.tracker.game.repository.LifetimeStatsUi
import com.adsamcik.tracker.game.repository.PersonalRecordUi
import com.adsamcik.tracker.game.repository.TrophyItemUi
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
@DisplayName("TrophyCaseViewModel")
class TrophyCaseViewModelTest {

	private val testDispatcher = StandardTestDispatcher()
	private lateinit var gameRepository: GameRepository

	private val historyFlow = MutableStateFlow(sampleTrophies())

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		gameRepository = mockk(relaxed = true)

		every { gameRepository.getChallengeHistory() } returns historyFlow
		every { gameRepository.getPersonalRecords() } returns flowOf(emptyList())
		every { gameRepository.getLifetimeStats() } returns flowOf(
			LifetimeStatsUi(0, 0, 0f, 0, 0, 0, 0L),
		)
		every { gameRepository.getActiveChallenges() } returns MutableStateFlow(emptyList())
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel() = TrophyCaseViewModel(gameRepository = gameRepository)

	private fun sampleTrophies() = listOf(
		TrophyItemUi(1L, "WALK", "EASY", "GOLD", 1000L, 100, 5000.0, 5000.0),
		TrophyItemUi(2L, "STEPS", "MEDIUM", "SILVER", 2000L, 150, 8000.0, 10000.0),
		TrophyItemUi(3L, "SPEED", "HARD", "BRONZE", 3000L, 200, 3000.0, 5000.0),
		TrophyItemUi(4L, "WALK", "MEDIUM", "GOLD", 4000L, 120, 10000.0, 10000.0),
		TrophyItemUi(5L, "STEPS", "EASY", null, 5000L, 50, 1000.0, 5000.0),
	)

	@Nested
	@DisplayName("filter")
	inner class Filter {

		@Test
		fun `default filter is ALL`() {
			val vm = createViewModel()
			vm.filter.value shouldBe TrophyFilter.ALL
		}

		@Test
		fun `setFilter updates filter state`() {
			val vm = createViewModel()
			vm.setFilter(TrophyFilter.GOLD)
			vm.filter.value shouldBe TrophyFilter.GOLD
		}

		@Test
		fun `setFilter to same value is idempotent`() {
			val vm = createViewModel()
			vm.setFilter(TrophyFilter.SILVER)
			vm.setFilter(TrophyFilter.SILVER)
			vm.filter.value shouldBe TrophyFilter.SILVER
		}
	}

	@Nested
	@DisplayName("trophies filtering")
	inner class TrophiesFiltering {

		@Test
		fun `ALL filter returns all trophies`() = runTest {
			val vm = createViewModel()

			vm.trophies.test {
				skipItems(1) // skip initial null
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 5
			}
		}

		@Test
		fun `GOLD filter returns only gold medals`() = runTest {
			val vm = createViewModel()
			vm.setFilter(TrophyFilter.GOLD)

			vm.trophies.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 2
				result.all { it.medal == "GOLD" } shouldBe true
			}
		}

		@Test
		fun `SILVER filter returns only silver medals`() = runTest {
			val vm = createViewModel()
			vm.setFilter(TrophyFilter.SILVER)

			vm.trophies.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 1
				result[0].medal shouldBe "SILVER"
			}
		}

		@Test
		fun `BRONZE filter returns only bronze medals`() = runTest {
			val vm = createViewModel()
			vm.setFilter(TrophyFilter.BRONZE)

			vm.trophies.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 1
				result[0].medal shouldBe "BRONZE"
			}
		}

		@Test
		fun `filter change updates emitted trophies`() = runTest {
			val vm = createViewModel()

			vm.trophies.test {
				skipItems(1) // skip null
				awaitItem().shouldNotBeNull() // ALL: 5 items

				vm.setFilter(TrophyFilter.GOLD)
				val goldOnly = awaitItem()
				goldOnly.shouldNotBeNull()
				goldOnly shouldHaveSize 2

				vm.setFilter(TrophyFilter.ALL)
				val allAgain = awaitItem()
				allAgain.shouldNotBeNull()
				allAgain shouldHaveSize 5
			}
		}

		@Test
		fun `empty history results in empty filtered list`() = runTest {
			historyFlow.value = emptyList()
			val vm = createViewModel()
			vm.setFilter(TrophyFilter.GOLD)

			vm.trophies.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result.shouldBeEmpty()
			}
		}
	}

	@Nested
	@DisplayName("personalRecords")
	inner class PersonalRecords {

		@Test
		fun `emits personal records from repository`() = runTest {
			val records = listOf(
				PersonalRecordUi("WALK", "distance", 15000.0, 1000L),
			)
			every { gameRepository.getPersonalRecords() } returns flowOf(records)

			val vm = createViewModel()

			vm.personalRecords.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 1
				result[0].metric shouldBe "distance"
			}
		}
	}

	@Nested
	@DisplayName("lifetimeStats")
	inner class LifetimeStats {

		@Test
		fun `emits lifetime stats from repository`() = runTest {
			val stats = LifetimeStatsUi(
				totalChallenges = 20,
				completedCount = 15,
				completionRate = 0.75f,
				goldCount = 5,
				silverCount = 6,
				bronzeCount = 4,
				totalXpEarned = 5000L,
			)
			every { gameRepository.getLifetimeStats() } returns flowOf(stats)

			val vm = createViewModel()

			vm.lifetimeStats.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result.totalChallenges shouldBe 20
				result.completionRate shouldBe 0.75f
			}
		}
	}

	@Nested
	@DisplayName("activeChallenges")
	inner class ActiveChallenges {

		@Test
		fun `maps ChallengeData to ChallengeUi`() = runTest {
			val data = listOf(
				ChallengeData(1L, "Test Challenge", "Description", 0.6f, "HARD", 1800_000L),
			)
			every { gameRepository.getActiveChallenges() } returns MutableStateFlow(data)

			val vm = createViewModel()

			vm.activeChallenges.test {
				skipItems(1)
				val result = awaitItem()
				result.shouldNotBeNull()
				result shouldHaveSize 1
				result[0].title shouldBe "Test Challenge"
				result[0].progress shouldBe 0.6f
				result[0].difficulty shouldBe "HARD"
			}
		}
	}
}
