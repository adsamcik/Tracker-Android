package com.adsamcik.tracker.game.viewmodel

import app.cash.turbine.test
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@DisplayName("ExplorationViewModel")
class ExplorationViewModelTest {

	private val testDispatcher = StandardTestDispatcher()

	private lateinit var explorationCellDao: ExplorationCellDao
	private lateinit var explorationStreakDao: ExplorationStreakDao
	private lateinit var achievementProgressDao: AchievementProgressDao

	@BeforeEach
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
		explorationCellDao = mockk(relaxed = true)
		explorationStreakDao = mockk(relaxed = true)
		achievementProgressDao = mockk(relaxed = true)

		// Default stubs
		every { explorationCellDao.countAtLevelFlow(any()) } returns flowOf(0)
		every { explorationStreakDao.getByTypeFlow(any()) } returns flowOf(null)
		every { explorationCellDao.getDistinctSeasonBitmasksFlow(any()) } returns flowOf(emptyList())
		every { explorationCellDao.getRecentAtLevelFlow(any(), any()) } returns flowOf(emptyList())
		every { achievementProgressDao.getAllFlow() } returns flowOf(emptyList())
	}

	@AfterEach
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun createViewModel() = ExplorationViewModel(
		explorationCellDao = explorationCellDao,
		explorationStreakDao = explorationStreakDao,
		achievementProgressDao = achievementProgressDao,
		dispatchers = TestDispatchersProvider(testDispatcher),
	)

	private fun makeCell(token: String, quality: Int = 2, discoveredAt: Long = 1000L) =
		ExplorationCellEntity(
			cellToken = token,
			level = 14,
			quality = quality,
			firstDiscoveredAt = discoveredAt,
			lastVisitedAt = discoveredAt,
			visitCount = 1,
			seasonBitmask = 0,
			centerLatE7 = 500000000,
			centerLonE7 = 140000000,
			createdAt = discoveredAt,
		)

	@Nested
	@DisplayName("ExplorationState")
	inner class ExplorationStateTests {

		@Test
		fun `initial value is null`() = runTest {
			val vm = createViewModel()
			vm.explorationState.value.shouldBeNull()
		}

		@Test
		fun `totalCells reflects DAO count`() = runTest {
			every { explorationCellDao.countAtLevelFlow(14) } returns flowOf(42)
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.totalCells shouldBe 42
			}
		}

		@Test
		fun `streak data from DAO is mapped correctly`() = runTest {
			val streak = ExplorationStreakEntity(
				type = "DAILY_DISCOVERY",
				currentCount = 7,
				bestCount = 14,
				lastIncrementDay = 19800,
				updatedAt = System.currentTimeMillis(),
			)
			every { explorationStreakDao.getByTypeFlow("DAILY_DISCOVERY") } returns flowOf(streak)
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.dailyStreak shouldBe 7
				state.bestStreak shouldBe 14
			}
		}

		@Test
		fun `null streak defaults to zero counts`() = runTest {
			every { explorationStreakDao.getByTypeFlow("DAILY_DISCOVERY") } returns flowOf(null)
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.dailyStreak shouldBe 0
				state.bestStreak shouldBe 0
			}
		}

		@Test
		fun `season bitmasks are ORed together`() = runTest {
			// spring=1, summer=2, winter=8
			every { explorationCellDao.getDistinctSeasonBitmasksFlow(14) } returns flowOf(
				listOf(1, 2, 8),
			)
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.seasonsBitmask shouldBe (1 or 2 or 8) // 11
			}
		}

		@Test
		fun `empty season bitmasks yields 0`() = runTest {
			every { explorationCellDao.getDistinctSeasonBitmasksFlow(14) } returns flowOf(emptyList())
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.seasonsBitmask shouldBe 0
			}
		}

		@Test
		fun `all four seasons ORed yields 15`() = runTest {
			every { explorationCellDao.getDistinctSeasonBitmasksFlow(14) } returns flowOf(
				listOf(1, 2, 4, 8),
			)
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.seasonsBitmask shouldBe 15
			}
		}

		@Test
		fun `recent discoveries are mapped from entities`() = runTest {
			val cells = listOf(
				makeCell("abc123", quality = 3, discoveredAt = 5000L),
				makeCell("def456", quality = 1, discoveredAt = 3000L),
			)
			every { explorationCellDao.getRecentAtLevelFlow(14, 5) } returns flowOf(cells)
			val vm = createViewModel()

			vm.explorationState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.recentDiscoveries.size shouldBe 2
				state.recentDiscoveries[0].token shouldBe "abc123"
				state.recentDiscoveries[0].quality shouldBe 3
				state.recentDiscoveries[0].discoveredAt shouldBe 5000L
				state.recentDiscoveries[1].token shouldBe "def456"
			}
		}
	}

	@Nested
	@DisplayName("AchievementSummaryState")
	inner class AchievementSummaryTests {

		private fun makeAchievement(
			id: String,
			tier: Int?,
			current: Long,
			target: Long,
			unlockedAt: Long? = null,
		) = AchievementProgressEntity(
			achievementId = id,
			currentValue = current,
			targetValue = target,
			tier = tier,
			unlockedAt = unlockedAt,
			updatedAt = System.currentTimeMillis(),
		)

		@Test
		fun `initial value is null`() = runTest {
			val vm = createViewModel()
			vm.achievementState.value.shouldBeNull()
		}

		@Test
		fun `tier counting is correct`() = runTest {
			val achievements = listOf(
				makeAchievement("a1", tier = 0, current = 100, target = 100, unlockedAt = 1000L), // bronze
				makeAchievement("a2", tier = 1, current = 200, target = 200, unlockedAt = 2000L), // silver
				makeAchievement("a3", tier = 2, current = 300, target = 300, unlockedAt = 3000L), // gold
				makeAchievement("a4", tier = 3, current = 400, target = 400, unlockedAt = 4000L), // diamond
				makeAchievement("a5", tier = 0, current = 50, target = 100, unlockedAt = 5000L),  // bronze
			)
			every { achievementProgressDao.getAllFlow() } returns flowOf(achievements)
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.bronzeCount shouldBe 2
				state.silverCount shouldBe 1
				state.goldCount shouldBe 1
				state.diamondCount shouldBe 1
				state.totalUnlocked shouldBe 5
			}
		}

		@Test
		fun `unlocked achievements have non-null unlockedAt`() = runTest {
			val achievements = listOf(
				makeAchievement("a1", tier = 0, current = 100, target = 100, unlockedAt = 1000L),
				makeAchievement("a2", tier = null, current = 50, target = 100, unlockedAt = null), // not unlocked
			)
			every { achievementProgressDao.getAllFlow() } returns flowOf(achievements)
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.totalUnlocked shouldBe 1
			}
		}

		@Test
		fun `nextClosest picks highest progress ratio among locked`() = runTest {
			val achievements = listOf(
				makeAchievement("a1", tier = 0, current = 100, target = 100, unlockedAt = 1000L), // unlocked
				makeAchievement("a2", tier = null, current = 80, target = 100, unlockedAt = null), // 80%
				makeAchievement("a3", tier = null, current = 30, target = 100, unlockedAt = null), // 30%
			)
			every { achievementProgressDao.getAllFlow() } returns flowOf(achievements)
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.nextClosest.shouldNotBeNull()
				state.nextClosest!!.id shouldBe "a2"
				state.nextClosest!!.progress shouldBe 0.8f
			}
		}

		@Test
		fun `nextClosest is null when all are unlocked`() = runTest {
			val achievements = listOf(
				makeAchievement("a1", tier = 0, current = 100, target = 100, unlockedAt = 1000L),
			)
			every { achievementProgressDao.getAllFlow() } returns flowOf(achievements)
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.nextClosest.shouldBeNull()
			}
		}

		@Test
		fun `nextClosest progress is capped at 1f`() = runTest {
			val achievements = listOf(
				// currentValue > targetValue edge case
				makeAchievement("a1", tier = null, current = 200, target = 100, unlockedAt = null),
			)
			every { achievementProgressDao.getAllFlow() } returns flowOf(achievements)
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.nextClosest.shouldNotBeNull()
				state.nextClosest!!.progress shouldBeLessThanOrEqual 1f
			}
		}

		@Test
		fun `achievements with zero targetValue are excluded from nextClosest`() = runTest {
			val achievements = listOf(
				makeAchievement("a1", tier = null, current = 50, target = 0, unlockedAt = null),
				makeAchievement("a2", tier = null, current = 40, target = 100, unlockedAt = null),
			)
			every { achievementProgressDao.getAllFlow() } returns flowOf(achievements)
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.nextClosest.shouldNotBeNull()
				state.nextClosest!!.id shouldBe "a2"
			}
		}

		@Test
		fun `empty achievement list yields zero counts`() = runTest {
			every { achievementProgressDao.getAllFlow() } returns flowOf(emptyList())
			val vm = createViewModel()

			vm.achievementState.test {
				skipItems(1)
				val state = awaitItem()
				state.shouldNotBeNull()
				state.bronzeCount shouldBe 0
				state.silverCount shouldBe 0
				state.goldCount shouldBe 0
				state.diamondCount shouldBe 0
				state.totalUnlocked shouldBe 0
				state.nextClosest.shouldBeNull()
			}
		}
	}
}
