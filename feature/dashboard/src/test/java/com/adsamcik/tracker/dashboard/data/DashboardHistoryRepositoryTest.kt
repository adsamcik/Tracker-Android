package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.dao.TripDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryMember
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.PressureAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.PressureAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.achievement.AchievementCatalog
import com.adsamcik.tracker.stats.api.metric.MetricKey
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
	fun `imported Steps is discoverable without a raw physical candidate or fake sample count`() = runTest {
		val imported = ImportedStepsHistoryEntry(
			TrackingHistoryEntryKey("imported-steps:entry"), EpochMs(10L), EpochMs(20L),
			listOf(ImportedStepsHistoryMember(42L, EpochMs(10L), EpochMs(20L), StepsHistory(
				count = 4L, availability = HistoryAvailability.RETAINED_IMPORTED,
				evidence = HistoryEvidence.RECORDED, productState = HistoryProductState.READY,
				coverage = StepsHistoryCoverage.COMPLETE, causes = emptySet(),
			))),
		)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(emptyList())
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(emptyList(), RECENT_HISTORY_LIMIT)
		} returns flowOf(PressureAwareHistoryPageQuery.Content(
			listOf(PressureAwareHistoryPageEntry.ImportedSteps(imported)),
		))
		repository(testScheduler).observeRecentHistory().first() shouldContainExactly
			listOf(DashboardRecentHistoryEntry.ImportedSteps(imported))
	}

	@Test
	fun `recent history coordinates exact candidate generation and preserves Stats ordering`() = runTest {
		val candidates = (20L downTo 1L).map(::trip)
		val steps = stepsEntry("zero-sample", 25_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(
				candidateSegmentIds = candidates.map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			PressureAwareHistoryPageQuery.Content(
				listOf(
					PressureAwareHistoryPageEntry.Physical(3L),
					PressureAwareHistoryPageEntry.StepsOnly(steps),
					PressureAwareHistoryPageEntry.Physical(20L),
					PressureAwareHistoryPageEntry.Physical(2L),
					PressureAwareHistoryPageEntry.Physical(1L),
				),
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
			trackingHistoryRepository.observeRecentPressureAwarePage(
				candidateSegmentIds = candidates.map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		}
	}

	@Test
	fun `replacement generation cancels old page and maps matching physical snapshot`() = runTest {
		val candidateRows = MutableStateFlow(listOf(trip(1L)))
		val firstPage = MutableStateFlow<PressureAwareHistoryPageQuery>(
			PressureAwareHistoryPageQuery.Content(
				listOf(PressureAwareHistoryPageEntry.Physical(1L)),
			),
		)
		val secondPage = MutableStateFlow<PressureAwareHistoryPageQuery>(
			PressureAwareHistoryPageQuery.Content(
				listOf(PressureAwareHistoryPageEntry.Physical(2L)),
			),
		)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns candidateRows
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns firstPage
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(listOf(2L), RECENT_HISTORY_LIMIT)
		} returns secondPage
		val pages = mutableListOf<List<DashboardRecentHistoryEntry>>()
		val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
			repository(testScheduler).observeRecentHistory().take(2).toList(pages)
		}

		advanceUntilIdle()
		candidateRows.value = listOf(trip(2L))
		advanceUntilIdle()
		firstPage.value = PressureAwareHistoryPageQuery.Content(emptyList())
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
			trackingHistoryRepository.observeRecentPressureAwarePage(any(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			PressureAwareHistoryPageQuery.Content(
				listOf(PressureAwareHistoryPageEntry.StepsOnly(steps)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.StepsOnly(steps))
		verify(exactly = 1) {
			trackingHistoryRepository.observeRecentPressureAwarePage(any(), RECENT_HISTORY_LIMIT)
		}
	}

	@Test
	fun `opaque Pressure row is mapped without a physical detail identity`() = runTest {
		val candidates = listOf(trip(1L))
		val pressure = pressureEntry("pressure-one", 2_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(
				listOf(1L),
				RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			PressureAwareHistoryPageQuery.Content(
				listOf(PressureAwareHistoryPageEntry.PressureOnly(pressure)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.PressureOnly(pressure))
	}

	@Test
	fun `Stats page failure is propagated without raw fallback`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(listOf(trip(1L)))
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns flow { throw IllegalStateException("history unavailable") }

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `typed Stats unavailability is propagated without raw fallback`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(listOf(trip(1L)))
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			PressureAwareHistoryPageQuery.Unavailable(
				PressureAwareHistoryPageUnavailableReason.LOGICAL_MEMBERSHIP_LIMIT,
			),
		)

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `candidate overflow fails closed when the bounded page remains underfilled`() = runTest {
		val candidates = (PHYSICAL_CANDIDATE_LIMIT.toLong() downTo 1L).map(::trip)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(any(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			PressureAwareHistoryPageQuery.Content(
				listOf(PressureAwareHistoryPageEntry.Physical(candidates.first().id)),
			),
		)

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `bounded backfill may complete from the first sixty four of an overflow probe`() = runTest {
		val candidates = (PHYSICAL_CANDIDATE_LIMIT.toLong() downTo 1L).map(::trip)
		val expectedIds = candidates.take(RECENT_HISTORY_LIMIT).map { it.id }
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_LIMIT) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentPressureAwarePage(
				candidateSegmentIds = candidates.take(64).map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			PressureAwareHistoryPageQuery.Content(
				expectedIds.map { PressureAwareHistoryPageEntry.Physical(it) },
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page.map { (it as DashboardRecentHistoryEntry.Physical).trip.id } shouldBe expectedIds
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

	@Test
	fun `latest achievement skips unavailable rows and orders by exact unlock time`() = runTest {
		stubSuccessfulOptionalDefaults()
		coEvery { achievementProgressDao.getAll() } returns listOf(
			AchievementProgressEntity(
				metricKey = MetricKey.GOAL_STREAK_DAYS.storageKey,
				lastTierIndex = 0,
				lastValue = 3.0,
				updatedAt = 300L,
				lastUnlockedAt = 300L,
				authorityKind = AchievementProgressEntity.AUTHORITY_QUALIFIED_STEPS_V1,
				authorityRevision = 4L,
				authorityDigest = "a".repeat(64),
				authorityState = AchievementProgressEntity.AUTHORITY_STATE_MATERIALIZING,
				qualifiedNotificationClaimedTierIndex = 0,
			),
			achievementProgress(MetricKey.DISTANCE_TOTAL_M, updatedAt = 1_000L, unlockedAt = 10L),
			achievementProgress(MetricKey.SESSIONS_TOTAL, updatedAt = 500L, unlockedAt = 20L),
		)

		val latest = (repository(testScheduler).load().latestAchievement as
			DashboardHistorySection.Loaded).value

		latest shouldBe DashboardAchievementHistory(
			id = AchievementCatalog.byMetric(MetricKey.SESSIONS_TOTAL).first().id,
			nameRes = AchievementCatalog.byMetric(MetricKey.SESSIONS_TOTAL).first().nameRes,
			tier = AchievementCatalog.byMetric(MetricKey.SESSIONS_TOTAL).first().tier,
			unlockedAt = 20L,
		)
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

	private fun achievementProgress(
		metric: MetricKey,
		updatedAt: Long,
		unlockedAt: Long,
	) = AchievementProgressEntity(
		metricKey = metric.storageKey,
		lastTierIndex = 0,
		lastValue = AchievementCatalog.byMetric(metric).first().threshold,
		updatedAt = updatedAt,
		lastUnlockedAt = unlockedAt,
	)

	private fun stepsEntry(key: String, startTimeMs: Long) = StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey(key),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(startTimeMs + 500L),
		state = StepsOnlyHistoryListState.AVAILABLE,
	)

	private fun pressureEntry(key: String, startTimeMs: Long) = PressureOnlyHistoryEntry(
		key = TrackingHistoryEntryKey(key),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(startTimeMs + 500L),
		pressure = PressureHistory(
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = PressureHistoryCoverage.UNKNOWN,
			windows = emptyList(),
			causes = setOf(PressureHistoryCause.RETENTION_TRUNCATED),
		),
	)

	private companion object {
		const val EXPLORATION_LEVEL = 14
		const val PHYSICAL_CANDIDATE_LIMIT = 65
		const val RECENT_HISTORY_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val STREAK_TYPE = "DAILY_DISCOVERY"
	}
}
