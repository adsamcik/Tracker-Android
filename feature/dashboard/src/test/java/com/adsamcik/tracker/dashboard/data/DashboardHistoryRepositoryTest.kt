package com.adsamcik.tracker.dashboard.data

import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.database.dao.AchievementProgressDao
import com.adsamcik.tracker.shared.base.database.dao.DailySummaryDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import com.adsamcik.tracker.shared.base.database.dao.ExplorationStreakDao
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.ExplorationStreakEntity
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.FixedClock
import com.adsamcik.tracker.shared.base.time.SystemClock
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
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageProblem
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageQuery
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardHistoryRepositoryTest {
	private val trackingHistoryRepository = mockk<TrackingHistoryRepository>()
	private val explorationCellDao = mockk<ExplorationCellDao>()
	private val explorationStreakDao = mockk<ExplorationStreakDao>()
	private val dailySummaryDao = mockk<DailySummaryDao>()
	private val achievementProgressDao = mockk<AchievementProgressDao>()

	@Test
	fun `a successful empty shared page stays empty in both recent APIs`() = runTest {
		every { trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT) } returns
			flowOf(SourceAwareHistoryPageQuery.Content(emptyList()))
		val repository = repository(testScheduler)

		repository.observeRecentHistoryState().first() shouldBe DashboardRecentHistoryState.Content(emptyList())
		repository.observeRecentHistory().first() shouldBe emptyList()
	}

	@Test
	fun `shared corrected Location measurements update both recent APIs without a second read`() = runTest {
		val stored = trip(1L)
		val measured = stored.toModel()
		val changes = MutableSharedFlow<SourceAwareHistoryPageQuery>(replay = 1)
		changes.emit(SourceAwareHistoryPageQuery.Content(listOf(SourceAwareHistoryPageEntry.Physical(1L)),
			physicalTrips = mapOf(1L to measured)))
		every { trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT) } returns changes
		val received = mutableListOf<List<DashboardRecentHistoryEntry>>()
		val collecting = launch { repository(testScheduler).observeRecentHistory().take(2).toList(received) }
		advanceUntilIdle()
		changes.emit(SourceAwareHistoryPageQuery.Content(listOf(SourceAwareHistoryPageEntry.Physical(1L)),
			physicalTrips = mapOf(1L to measured.copy(distanceM = 0f, hasMeasuredDistance = true))))
		advanceUntilIdle()
		collecting.join()

		(received.first().single() as DashboardRecentHistoryEntry.Physical).trip shouldBe measured
		val stationary = (received.last().single() as DashboardRecentHistoryEntry.Physical).trip
		stationary.distanceM shouldBe 0f
		stationary.hasMeasuredDistance shouldBe true
		val current = repository(testScheduler).observeRecentHistoryState().first() as DashboardRecentHistoryState.Content
		current.entries shouldBe received.last()
		stored.distanceM shouldBe 100f
	}

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
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
		} returns flowOf(SourceAwareHistoryPageQuery.Content(
			listOf(SourceAwareHistoryPageEntry.ImportedSteps(imported)),
		))
		repository(testScheduler).observeRecentHistory().first() shouldContainExactly
			listOf(DashboardRecentHistoryEntry.ImportedSteps(imported))
	}

	@Test
	fun `recent history preserves shared source ordering and honest physical measurements`() = runTest {
		val steps = stepsEntry("zero-sample", 25_000L)
		val measured = trip(3L).toModel().copy(distanceM = 321f, distanceIsPartial = true)
		val unknown = trip(20L).toModel().copy(distanceM = 0f, hasMeasuredDistance = false, distanceIsPartial = true)
		every { trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT) } returns flowOf(
			SourceAwareHistoryPageQuery.Content(listOf(SourceAwareHistoryPageEntry.Physical(3L),
				SourceAwareHistoryPageEntry.StepsOnly(steps), SourceAwareHistoryPageEntry.Physical(20L)),
				physicalTrips = mapOf(20L to unknown, 3L to measured)))

		val state = repository(testScheduler).observeRecentHistoryState().first() as DashboardRecentHistoryState.Content

		state.entries shouldContainExactly listOf(DashboardRecentHistoryEntry.Physical(measured),
			DashboardRecentHistoryEntry.StepsOnly(steps), DashboardRecentHistoryEntry.Physical(unknown))
		state.problems shouldBe emptySet()
	}

	@Test
	fun `a Steps-only shared row does not need a physical trip`() = runTest {
		val steps = stepsEntry("suppressed", 30_000L)
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.StepsOnly(steps)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.StepsOnly(steps))
	}

	@Test
	fun `Activity action handoff retains the exact imported hierarchy without a Trip row`() = runTest {
		val activity = importedActivityEntry("activity-only", 40_000L)
		val sourceEntry = SourceAwareHistoryPageEntry.ActivityOnly(activity)
		val readSnapshot = TrackingHistoryReadSnapshot(7L, 11L)
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
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
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
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
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
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
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
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
	fun `typed shared page overflow remains unavailable instead of showing an empty history`() = runTest {
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Unavailable(
				SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT,
			),
		)

		val repository = repository(testScheduler)
		repository.observeRecentHistoryState().first() shouldBe DashboardRecentHistoryState.Unavailable(
			SourceAwareHistoryPageUnavailableReason.CANDIDATE_SCAN_LIMIT)
		shouldThrow<DashboardHistoryPageUnavailable> { repository.observeRecentHistory().first() }
	}

	@Test
	fun `opaque Pressure row is mapped without a physical detail identity`() = runTest {
		val pressure = pressureEntry("pressure-one", 2_000L)
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Content(
				listOf(SourceAwareHistoryPageEntry.PressureOnly(pressure)),
			),
		)

		val page = repository(testScheduler).observeRecentHistory().first()

		page shouldContainExactly listOf(DashboardRecentHistoryEntry.PressureOnly(pressure))
	}

	@Test
	fun `partial source failure retains qualified Location and exposes the same problem`() = runTest {
		val physical = trip(1L).toModel()
		val problem = SourceAwareHistoryPageProblem(HistorySource.PRESSURE,
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		every { trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT) } returns flowOf(
			SourceAwareHistoryPageQuery.Content(listOf(SourceAwareHistoryPageEntry.Physical(physical.id)),
				problems = setOf(problem), physicalTrips = mapOf(physical.id to physical)))

		val state = repository(testScheduler).observeRecentHistoryState().first() as DashboardRecentHistoryState.Content

		state.entries shouldContainExactly listOf(DashboardRecentHistoryEntry.Physical(physical))
		state.problems shouldBe setOf(problem)
		repository(testScheduler).observeRecentHistory().first() shouldBe state.entries
	}

	@Test
	fun `Pressure recency budget and integrity failures remain typed unavailable`() = runTest {
		listOf(
			SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE,
			SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED,
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
		).forEach { reason ->
			every {
				trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
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
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
		} returns flow { throw IllegalStateException("history unavailable") }

		shouldThrow<IllegalStateException> {
			repository(testScheduler).observeRecentHistory().first()
		}
	}

	@Test
	fun `typed Stats unavailability is propagated without raw fallback`() = runTest {
		every {
			trackingHistoryRepository.observeRecentHistoryPage(RECENT_HISTORY_LIMIT)
		} returns flowOf(
			SourceAwareHistoryPageQuery.Unavailable(
				reason = SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				source = HistorySource.WIFI,
			),
		)

		val repository = repository(testScheduler)
		repository.observeRecentHistoryState().first() shouldBe DashboardRecentHistoryState.Unavailable(
			SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE, HistorySource.WIFI)
		val unavailable = shouldThrow<DashboardHistoryPageUnavailable> { repository.observeRecentHistory().first() }
		unavailable.reason shouldBe SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
		unavailable.source shouldBe HistorySource.WIFI
	}

	@Test
	fun `weekly history and streak expiry use Prague calendar days across daylight saving changes`() = runTest {
		val originalZone = TimeZone.getDefault()
		val zone = ZoneId.of("Europe/Prague")
		try {
			TimeZone.setDefault(TimeZone.getTimeZone(zone))
			for (day in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
				val now = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
				val epochDay = day.toEpochDay()
				stubSuccessfulOptionalDefaults()
				coEvery { explorationStreakDao.getByType(STREAK_TYPE) } returns ExplorationStreakEntity(
					STREAK_TYPE, currentCount = 4, bestCount = 9, lastIncrementDay = epochDay - 2L, updatedAt = now)
				coEvery { dailySummaryDao.getBetween(any(), any()) } returns (0 until DASHBOARD_DAY_COUNT).map { index ->
					DailySummaryEntity(epochDay - 6L + index, (index + 1) * 10f, 0, 0L, 1, 0L, now, now, zone.id)
				}

				val streak = (repository(testScheduler, FixedClock(now)).load().streak as DashboardHistorySection.Loaded).value

				coVerify(exactly = 1) { dailySummaryDao.getBetween(epochDay - 6L, epochDay) }
				streak.weeklyDistances shouldBe listOf(10f, 20f, 30f, 40f, 50f, 60f, 70f)
				streak.currentStreak shouldBe 0
				streak.bestStreak shouldBe 9
			}
		} finally {
			TimeZone.setDefault(originalZone)
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

	private fun repository(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler, clock: Clock = SystemClock) =
		RoomDashboardHistoryRepository(
			trackingHistoryRepository = trackingHistoryRepository,
			explorationCellDao = explorationCellDao,
			explorationStreakDao = explorationStreakDao,
			dailySummaryDao = dailySummaryDao,
			achievementProgressDao = achievementProgressDao,
			dispatchers = TestDispatchersProvider(StandardTestDispatcher(testScheduler)),
			clock = clock,
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
		const val RECENT_HISTORY_LIMIT = 5
		const val DASHBOARD_DAY_COUNT = 7
		const val STREAK_TYPE = "DAILY_DISCOVERY"
	}
}
