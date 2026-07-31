package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.model.SegmentSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DashboardHistoryRepositoryTest {
	private val tripDao = mockk<TripDao>()
	private val explorationCellDao = mockk<ExplorationCellDao>()
	private val explorationStreakDao = mockk<ExplorationStreakDao>()
	private val dailySummaryDao = mockk<DailySummaryDao>()
	private val achievementProgressDao = mockk<AchievementProgressDao>()
	@Test
	fun `exploration failure preserves recent and last trips`() = runTest {
		val failure = IllegalStateException("exploration unavailable")
		stubSuccessfulDefaults()
		coEvery { explorationCellDao.countAtLevel(EXPLORATION_LEVEL) } throws failure

		val history = repository(testScheduler).load(includeLastSession = true)

		history.recentTrips.map { it.id } shouldContainExactly listOf(2L, 1L)
		history.lastSession?.id shouldBe 2L
		history.exploration shouldBe DashboardHistorySection.Failed
		history.streak shouldBe DashboardHistorySection.Loaded(
			DashboardStreakHistory(
				currentStreak = 0,
				bestStreak = 0,
				weeklyDistances = List(DASHBOARD_DAY_COUNT) { 0f },
				weeklyTrend = DashboardWeeklyTrend.STEADY,
			),
		)
		history.latestAchievement shouldBe DashboardHistorySection.Loaded(null)
		coVerify(exactly = 1) { achievementProgressDao.getAll() }
	}

	@Test
	fun `streak failure preserves trips and remains distinct from empty data`() = runTest {
		val failure = IllegalStateException("streak unavailable")
		stubSuccessfulDefaults()
		coEvery { explorationStreakDao.getByType(STREAK_TYPE) } throws failure

		val history = repository(testScheduler).load(includeLastSession = true)

		history.recentTrips.map { it.id } shouldContainExactly listOf(2L, 1L)
		history.lastSession?.id shouldBe 2L
		history.streak shouldBe DashboardHistorySection.Failed
		coVerify(exactly = 1) { achievementProgressDao.getAll() }
	}

	@Test
	fun `achievement failure preserves recent and last trips`() = runTest {
		val failure = IllegalStateException("achievements unavailable")
		stubSuccessfulDefaults()
		coEvery { achievementProgressDao.getAll() } throws failure

		val history = repository(testScheduler).load(includeLastSession = true)

		history.recentTrips.map { it.id } shouldContainExactly listOf(2L, 1L)
		history.lastSession?.id shouldBe 2L
		history.latestAchievement shouldBe DashboardHistorySection.Failed
	}

	private fun stubSuccessfulDefaults() {
		coEvery { tripDao.getRecentTrips(RECENT_TRIP_LIMIT) } returns listOf(
			trip(id = 2L, startTimeMs = 2_000L),
			trip(id = 1L, startTimeMs = 1_000L),
		)
		coEvery { explorationCellDao.countAtLevel(EXPLORATION_LEVEL) } returns 0
		coEvery { explorationStreakDao.getByType(STREAK_TYPE) } returns null
		coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()
		coEvery { achievementProgressDao.getAll() } returns emptyList()
	}

	private fun repository(testScheduler: TestCoroutineScheduler) = RoomDashboardHistoryRepository(
		tripDao = tripDao,
		explorationCellDao = explorationCellDao,
		explorationStreakDao = explorationStreakDao,
		dailySummaryDao = dailySummaryDao,
		achievementProgressDao = achievementProgressDao,
		dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
	)

	private fun trip(id: Long, startTimeMs: Long) = Trip(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = startTimeMs + 500L,
		distanceM = 100f,
		steps = 10,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 1,
		source = SegmentSource.USER_CREATED,
		createdAt = startTimeMs,
	)

	private companion object {
		const val EXPLORATION_LEVEL = 14
		const val RECENT_TRIP_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val STREAK_TYPE = "DAILY_DISCOVERY"
	}
}
