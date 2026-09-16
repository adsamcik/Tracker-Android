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
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistorySelection
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistoryRunDeletionScope
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistorySelection
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ImportedStepsHistoryMember
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryActionTarget
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCause
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiLocalHistorySelectionKey
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
			trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT)
		} returns flowOf(SourceAwareHistoryPageQuery.Content(
			listOf(SourceAwareHistoryPageEntry.ImportedSteps(imported)),
		))
		repository(testScheduler).observeRecentHistory().first() shouldContainExactly
			listOf(DashboardRecentHistoryEntry.ImportedSteps(imported))
	}

	@Test
	fun `recent history coordinates exact candidate generation and preserves Stats ordering`() = runTest {
		val candidates = (20L downTo 1L).map(::trip)
		val steps = stepsEntry("zero-sample", 25_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(
				candidateSegmentIds = candidates.map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(
					SourceAwareHistoryPageEntry.Physical(3L),
					SourceAwareHistoryPageEntry.StepsOnly(steps),
					SourceAwareHistoryPageEntry.Physical(20L),
					SourceAwareHistoryPageEntry.Physical(2L),
					SourceAwareHistoryPageEntry.Physical(1L),
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
		verify(exactly = 1) { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) }
		verify(exactly = 1) {
			trackingHistoryRepository.observeRecentSourceAwarePage(
				candidateSegmentIds = candidates.map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		}
	}

	@Test
	fun `replacement generation cancels old page and maps matching physical snapshot`() = runTest {
		val candidateRows = MutableStateFlow(listOf(trip(1L)))
		val firstPage = MutableStateFlow<SourceAwareHistoryPageQuery>(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.Physical(1L)),
			),
		)
		val secondPage = MutableStateFlow<SourceAwareHistoryPageQuery>(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.Physical(2L)),
			),
		)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns candidateRows
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns firstPage
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(listOf(2L), RECENT_HISTORY_LIMIT)
		} returns secondPage
		val pages = mutableListOf<List<DashboardRecentHistoryEntry>>()
		val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
			repository(testScheduler).observeRecentHistory().take(2).toList(pages)
		}

		advanceUntilIdle()
		candidateRows.value = listOf(trip(2L))
		advanceUntilIdle()
		firstPage.value = SourceAwareHistoryPageQuery.Content(emptyList())
		collection.join()

		pages.map { page -> (page.single() as DashboardRecentHistoryEntry.Physical).trip.id } shouldContainExactly
			listOf(1L, 2L)
	}

	@Test
	fun `suppressed physical becomes one opaque Steps row without backfill`() = runTest {
		val candidates = (20L downTo 1L).map(::trip)
		val steps = stepsEntry("suppressed", 30_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(any(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.StepsOnly(steps)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.StepsOnly(steps))
		verify(exactly = 1) {
			trackingHistoryRepository.observeRecentSourceAwarePage(any(), RECENT_HISTORY_LIMIT)
		}
	}

	@Test
	fun `Activity action handoff retains the exact imported hierarchy without a Trip row`() = runTest {
		val activity = importedActivityEntry("activity-only", 40_000L)
		val sourceEntry = SourceAwareHistoryPageEntry.ActivityOnly(activity)
		val readSnapshot = TrackingHistoryReadSnapshot(7L, 11L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				entries = listOf(sourceEntry),
				readSnapshot = readSnapshot,
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()
		val mapped = page.single() as DashboardRecentHistoryEntry.ActivityOnly
		val detailSelection = requireNotNull(mapped.detailSelection)
		val actionTarget = detailSelection.actionTarget as TrackingHistoryActionTarget.Activity

		mapped.history shouldBe activity
		detailSelection.entry shouldBe sourceEntry
		detailSelection.readSnapshot shouldBe readSnapshot
		actionTarget.selection shouldBe ActivityHistorySelection.Imported(
			requireNotNull(activity.importedSelection),
		)
		(actionTarget.selection as ActivityHistorySelection.Imported)
			.selected.runDeletionScopes shouldBe
			requireNotNull(activity.importedSelection).runDeletionScopes
		(actionTarget.selection as ActivityHistorySelection.Imported)
			.selected.windowIdentities shouldBe
			requireNotNull(activity.importedSelection).windowIdentities
	}

	@Test
	fun `Activity without an exact selector remains visible and nonactionable`() = runTest {
		val activity = importedActivityEntry("activity-retained", 40_000L).copy(
			importedSelection = null,
		)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				entries = listOf(SourceAwareHistoryPageEntry.ActivityOnly(activity)),
				readSnapshot = TrackingHistoryReadSnapshot(7L, 11L),
			),
		)

		repository(testScheduler).observeRecentHistory().first() shouldContainExactly listOf(
			DashboardRecentHistoryEntry.ActivityOnly(
				history = activity,
				detailSelection = null,
			),
		)
	}

	@Test
	fun `combined source page preserves one Activity and Pressure ordering`() = runTest {
		val activity = activityEntry("activity-only", 40_000L)
		val pressure = pressureEntry("pressure-only", 30_000L)
		val activitySourceEntry = SourceAwareHistoryPageEntry.ActivityOnly(activity)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(
					activitySourceEntry,
					SourceAwareHistoryPageEntry.PressureOnly(pressure),
				),
			),
		)

		repository(testScheduler).observeRecentHistory().first() shouldContainExactly listOf(
			DashboardRecentHistoryEntry.ActivityOnly(
				history = activity,
				detailSelection = SourceHistoryDetailSelection(activitySourceEntry, null),
			),
			DashboardRecentHistoryEntry.PressureOnly(pressure),
		)
	}

	@Test
	fun `radio rows retain producer selections and common read snapshot`() = runTest {
		val wifi = wifiEntry()
		val cell = cellEntry()
		val wifiSource = SourceAwareHistoryPageEntry.WifiOnly(wifi)
		val cellSource = SourceAwareHistoryPageEntry.CellOnly(cell)
		val readSnapshot = TrackingHistoryReadSnapshot(9L, 12L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(emptyList(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				entries = listOf(wifiSource, cellSource),
				readSnapshot = readSnapshot,
			),
		)

		repository(testScheduler).observeRecentHistory().first() shouldContainExactly listOf(
			DashboardRecentHistoryEntry.WifiOnly(
				history = wifi,
				detailSelection = SourceHistoryDetailSelection(wifiSource, readSnapshot),
			),
			DashboardRecentHistoryEntry.CellOnly(
				history = cell,
				detailSelection = SourceHistoryDetailSelection(cellSource, readSnapshot),
			),
		)
	}

	@Test
	fun `typed Activity page overflow never falls back to physical rows`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(listOf(trip(1L)))
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
			),
		)

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `physical overflow probe cannot publish a short recent page`() = runTest {
		val probe = (65L downTo 1L).map(::trip)
		val boundedIds = probe.take(64).map { it.id }
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(probe)
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(boundedIds, RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				boundedIds.take(RECENT_HISTORY_LIMIT - 1)
					.map { segmentId -> SourceAwareHistoryPageEntry.Physical(segmentId) },
			),
		)

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `opaque Pressure row is mapped without a physical detail identity`() = runTest {
		val candidates = listOf(trip(1L))
		val pressure = pressureEntry("pressure-one", 2_000L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(
				listOf(1L),
				RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.PressureOnly(pressure)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.PressureOnly(pressure))
	}

	@Test
	fun `failed Pressure replacement decision keeps the returned physical row`() = runTest {
		val physical = trip(1L)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns
			flowOf(listOf(physical))
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(
				listOf(physical.id),
				RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.Physical(physical.id)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(
			DashboardRecentHistoryEntry.Physical(physical.toModel()),
		)
		page.none { it is DashboardRecentHistoryEntry.PressureOnly } shouldBe true
	}

	@Test
	fun `Pressure recency budget and integrity failures remain typed unavailable`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(emptyList())
		listOf(
			SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		).forEach { reason ->
			every {
				trackingHistoryRepository.observeRecentSourceAwarePage(
					emptyList(),
					RECENT_HISTORY_LIMIT,
				)
			} returns flowOf(
				SourceAwareHistoryPageQuery.Unavailable(
					reason = reason,
					source = HistorySource.PRESSURE,
				),
			)

			val unavailable = shouldThrow<DashboardHistoryPageUnavailable> {
				repository(testScheduler).observeRecentHistory().first()
			}

			unavailable.reason shouldBe reason
			unavailable.source shouldBe HistorySource.PRESSURE
		}
	}

	@Test
	fun `Stats page failure is propagated without raw fallback`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(listOf(trip(1L)))
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns flow { throw IllegalStateException("history unavailable") }

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `typed Stats unavailability is propagated without raw fallback`() = runTest {
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(listOf(trip(1L)))
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(listOf(1L), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Unavailable(
				reason = SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				source = HistorySource.WIFI,
			),
		)

		val unavailable = shouldThrow<DashboardHistoryPageUnavailable> {
			repository(testScheduler).observeRecentHistory().first()
		}
		unavailable.reason shouldBe SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
		unavailable.source shouldBe HistorySource.WIFI
	}

	@Test
	fun `candidate overflow fails closed when the bounded page remains underfilled`() = runTest {
		val candidates = (PHYSICAL_CANDIDATE_PROBE.toLong() downTo 1L).map(::trip)
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(any(), RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.Physical(candidates.first().id)),
			),
		)

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `bounded backfill may complete from the first sixty four of an overflow probe`() = runTest {
		val candidates = (PHYSICAL_CANDIDATE_PROBE.toLong() downTo 1L).map(::trip)
		val expectedIds = candidates.take(RECENT_HISTORY_LIMIT).map { it.id }
		every { tripDao.getRecentTripsFlow(PHYSICAL_CANDIDATE_PROBE) } returns flowOf(candidates)
		every {
			trackingHistoryRepository.observeRecentSourceAwarePage(
				candidateSegmentIds = candidates.take(64).map { it.id },
				limit = RECENT_HISTORY_LIMIT,
			)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				expectedIds.map { SourceAwareHistoryPageEntry.Physical(it) },
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
		origin = PressureHistoryOrigin.Local,
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

	private fun activityEntry(key: String, startTimeMs: Long) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey(key),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(startTimeMs + 500L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.PARTIAL,
		coverage = ActivityHistoryCoverage.PARTIAL,
		activeTime = ActivityActiveTime(1L, 0L, 0L, 0L),
		fragments = emptyList(),
		causes = setOf(com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause.PROVIDER_GAP),
		capturesOnlyActivity = true,
	)

	private fun importedActivityEntry(
		key: String,
		startTimeMs: Long,
	): ActivityHistoryEntry {
		val entryKey = ActivityHistoryEntryKey(key)
		val selection = ActivityImportedHistorySelection(
			key = entryKey,
			identity = ActivityImportedHistoryIdentity("a".repeat(64)),
			importRevision = 3L,
			contentChecksum = ActivityImportedHistoryDigest("e".repeat(64)),
			runDeletionScopes = listOf(
				ActivityImportedHistoryRunDeletionScope(
					runIdentity = ActivityImportedHistoryIdentity("b".repeat(64)),
					deletionScopeDigest =
						ActivityImportedHistoryDeletionScopeDigest("c".repeat(64)),
				),
			),
			windowIdentities = listOf(ActivityImportedHistoryIdentity("d".repeat(64))),
			readSnapshot = ActivityImportedHistoryReadSnapshot(7L, 11L),
		)
		return activityEntry(key, startTimeMs).copy(
			origin = ActivityHistoryOrigin.IMPORTED,
			capturesOnlyActivity = false,
			importedSelection = selection,
		)
	}

	private fun wifiEntry() = WifiHistoryEntry(
		key = WifiHistoryEntryKey("wifi"),
		startTime = EpochMs(50_000L),
		endTime = EpochMs(51_000L),
		storedZoneIds = setOf("UTC"),
		state = WifiHistoryProductState.MATERIALIZING,
		coverage = WifiHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(WifiHistoryCause.MATERIALIZATION_BEHIND),
		localSelection = WifiLocalHistorySelectionKey("a".repeat(64)),
		capturesOnlyWifi = true,
	)

	private fun cellEntry() = CellHistoryEntry(
		key = CellHistoryEntryKey("cell"),
		startTime = EpochMs(49_000L),
		endTime = EpochMs(50_000L),
		storedZoneIds = setOf("UTC"),
		state = CellHistoryProductState.MATERIALIZING,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.MATERIALIZATION_BEHIND),
		selection = LocalCellHistorySelection(LocalCellHistoryIdentity("b".repeat(64))),
	)

	private companion object {
		const val EXPLORATION_LEVEL = 14
		const val PHYSICAL_CANDIDATE_PROBE = 65
		const val RECENT_HISTORY_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val STREAK_TYPE = "DAILY_DISCOVERY"
	}
}
