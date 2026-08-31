package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.StepsAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHistoryRepositoryTest {
	private val tripDao = mockk<TripDao>()
	private val trackingHistoryRepository = mockk<TrackingHistoryRepository>()
	private val explorationCellDao = mockk<ExplorationCellDao>()
	private val explorationStreakDao = mockk<ExplorationStreakDao>()
	private val dailySummaryDao = mockk<DailySummaryDao>()
	private val achievementProgressDao = mockk<AchievementProgressDao>()

	@Test
	fun `recent history coordinates exact candidate generation and preserves Stats ordering`() = runTest {
		val candidates = (20L downTo 1L).map(::trip)
		val steps = stepsEntry("zero-sample", 25_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentStepsAwarePage(
				candidateSegmentIds = candidates.map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			listOf(
				StepsAwareHistoryPageEntry.Physical(3L),
				StepsAwareHistoryPageEntry.StepsOnly(steps),
				StepsAwareHistoryPageEntry.Physical(20L),
				StepsAwareHistoryPageEntry.Physical(2L),
				StepsAwareHistoryPageEntry.Physical(1L),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(
			DashboardRecentHistoryEntry.Physical(candidates.first { it.id == 3L }.toModel()),
			DashboardRecentHistoryEntry.StepsOnly(steps),
			DashboardRecentHistoryEntry.Physical(candidates.first { it.id == 20L }.toModel()),
			DashboardRecentHistoryEntry.Physical(candidates.first { it.id == 2L }.toModel()),
			DashboardRecentHistoryEntry.Physical(candidates.first { it.id == 1L }.toModel()),
		)
		verify(exactly = 1) { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) }
		verify(exactly = 1) {
			trackingHistoryRepository.observeRecentStepsAwarePage(
				candidateSegmentIds = candidates.map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		}
	}

	@Test
	fun `replacement generation cancels old page and maps matching physical snapshot`() = runTest {
		val candidateRows = MutableStateFlow(listOf(trip(1L)))
		val firstPage = MutableStateFlow<List<StepsAwareHistoryPageEntry>>(
			listOf(StepsAwareHistoryPageEntry.Physical(1L)),
		)
		val secondPage = MutableStateFlow<List<StepsAwareHistoryPageEntry>>(
			listOf(StepsAwareHistoryPageEntry.Physical(2L)),
		)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns candidateRows
		every {
			trackingHistoryRepository.observeRecentStepsAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns firstPage
		every {
			trackingHistoryRepository.observeRecentStepsAwarePage(listOf(2L), RECENT_HISTORY_LIMIT)
		} returns secondPage
		val pages = mutableListOf<List<DashboardRecentHistoryEntry>>()
		val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
			repository(testScheduler).observeRecentHistory().take(2).toList(pages)
		}

		advanceUntilIdle()
		candidateRows.value = listOf(trip(2L))
		advanceUntilIdle()
		firstPage.value = emptyList()
		collection.join()

		pages.map { page -> (page.single() as DashboardRecentHistoryEntry.Physical).trip.id } shouldContainExactly
			listOf(1L, 2L)
	}

	@Test
	fun `suppressed physical becomes one opaque Steps row without backfill`() = runTest {
		val candidates = (20L downTo 1L).map(::trip)
		val steps = stepsEntry("suppressed", 30_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentStepsAwarePage(any(), RECENT_HISTORY_LIMIT)
		} returns flowOf(listOf(StepsAwareHistoryPageEntry.StepsOnly(steps)))

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.StepsOnly(steps))
		verify(exactly = 1) {
			trackingHistoryRepository.observeRecentStepsAwarePage(any(), RECENT_HISTORY_LIMIT)
		}
	}

	@Test
	fun `Stats page failure is propagated without raw fallback`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(listOf(trip(1L)))
		every {
			trackingHistoryRepository.observeRecentStepsAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns flow { throw IllegalStateException("history unavailable") }

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `optional section failure remains distinct from successful empty data`() = runTest {
		stubSuccessfulOptionalDefaults()
		coEvery { explorationCellDao.countAtLevel(EXPLORATION_LEVEL) } throws
			IllegalStateException("exploration unavailable")

		val history = repository(testScheduler).load()

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

	private fun stubSuccessfulOptionalDefaults() {
		coEvery { explorationCellDao.countAtLevel(EXPLORATION_LEVEL) } returns 0
		coEvery { explorationStreakDao.getByType(STREAK_TYPE) } returns null
		coEvery { dailySummaryDao.getBetween(any(), any()) } returns emptyList()
		coEvery { achievementProgressDao.getAll() } returns emptyList()
	}

	private fun repository(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
		RoomDashboardHistoryRepository(
			tripDao = tripDao,
			trackingHistoryRepository = trackingHistoryRepository,
			explorationCellDao = explorationCellDao,
			explorationStreakDao = explorationStreakDao,
			dailySummaryDao = dailySummaryDao,
			achievementProgressDao = achievementProgressDao,
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
		)

	private fun trip(id: Long) = Trip(
		id = id,
		startTimeMs = id * 1_000L,
		endTimeMs = id * 1_000L + 500L,
		distanceM = 100f,
		steps = 10,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 1,
		source = SegmentSource.USER_CREATED,
		createdAt = id * 1_000L,
	)

	private fun stepsEntry(key: String, startTimeMs: Long) = StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey(key),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(startTimeMs + 500L),
		state = StepsOnlyHistoryListState.AVAILABLE,
	)

	private companion object {
		const val EXPLORATION_LEVEL = 14
		const val PHYSICAL_CANDIDATE_LIMIT = 20
		const val RECENT_HISTORY_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val STREAK_TYPE = "DAILY_DISCOVERY"
	}
}
