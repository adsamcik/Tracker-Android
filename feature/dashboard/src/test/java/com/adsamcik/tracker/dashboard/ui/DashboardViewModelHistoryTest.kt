package com.adsamcik.tracker.dashboard.ui

import kotlinx.coroutines.test.TestScope
import com.adsamcik.tracker.tracker.data.session.TrackerSessionInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.data.DashboardAchievementHistory
import com.adsamcik.tracker.dashboard.data.DashboardExplorationHistory
import com.adsamcik.tracker.dashboard.data.DashboardHistory
import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardHistoryPageUnavailable
import com.adsamcik.tracker.dashboard.data.DashboardHistorySection
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryEntry
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.dashboard.data.DashboardStreakHistory
import com.adsamcik.tracker.dashboard.data.DashboardWeeklyTrend
import com.adsamcik.tracker.feature.statistics.api.navigation.SourceHistoryDetailSelection
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.LocalCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageEntry
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryRepository
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardViewModelHistoryTest {
	@Test
	fun `background-only host keeps recent history and Start state available`() = runTest {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try {
			val repository = QueuedDashboardHistoryRepository(successfulHistory())
			val viewModel = createViewModel(repository, collectionHost = MutableStateFlow(true))
			val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.recentHistory.collect { }
			}
			val liveObserver = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.isTracking.collect { }
			}
			runCurrent()
			viewModel.isTracking.value shouldBe false
			viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Content(emptyList())
			observer.cancel()
			liveObserver.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `transient optional failures preserve previously loaded cards`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val repository = QueuedDashboardHistoryRepository(
				successfulHistory(),
				failedOptionalHistory(),
			)
			val viewModel = createViewModel(repository)

			viewModel.loadHistoricalData(isTracking = false)
			advanceUntilIdle()

			val exploration = viewModel.explorationState.value
			val streak = viewModel.streakState.value
			val achievement = viewModel.latestAchievement.value

			viewModel.loadHistoricalData(isTracking = false)
			advanceUntilIdle()

			viewModel.explorationState.value shouldBe exploration
			viewModel.streakState.value shouldBe streak
			viewModel.latestAchievement.value shouldBe achievement
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `pending Start cancels idle history and hands its gate to live capture`() =
		runTest {
			val mainDispatcher = StandardTestDispatcher(testScheduler)
			Dispatchers.setMain(mainDispatcher)
			try {
				val tracking = MutableStateFlow(false)
				val starting = MutableStateFlow<Long?>(null)
				var starts = 0
				var cancellations = 0
				val repository = QueuedDashboardHistoryRepository(
					successfulHistory(),
					recentFlow = flow {
						starts += 1
						try {
							emit(emptyList())
							awaitCancellation()
						} finally {
							cancellations += 1
						}
					},
				)
				val viewModel = createViewModel(repository, tracking, startingGeneration = starting)
				val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
					viewModel.recentHistory.collect()
				}
				advanceUntilIdle()

				starts shouldBe 1
				viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Content(emptyList())

				starting.value = 69L
				advanceUntilIdle()
				viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Loading
				cancellations shouldBe 1
				starts shouldBe 1

				// Publishing the live session clears the same Start without restarting idle work.
				tracking.value = true
				advanceUntilIdle()
				starting.value = null
				advanceUntilIdle()
				starts shouldBe 1
				cancellations shouldBe 1

				tracking.value = false
				advanceUntilIdle()
				starts shouldBe 2
				collection.cancel()
			} finally {
				Dispatchers.resetMain()
			}
		}

	@Test
	fun `recent history failure becomes unavailable without raw fallback`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val repository = QueuedDashboardHistoryRepository(
				successfulHistory(),
				recentFlow = flow { throw IllegalStateException("history unavailable") },
			)
			val viewModel = createViewModel(repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.recentHistory.collect()
			}

			advanceUntilIdle()

			viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Unavailable()
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `typed recent history failure retains affected source`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val repository = QueuedDashboardHistoryRepository(
				successfulHistory(),
				recentFlow = flow {
					throw DashboardHistoryPageUnavailable(
						reason = SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
						source = HistorySource.WIFI,
					)
				},
			)
			val viewModel = createViewModel(repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.recentHistory.collect()
			}
			advanceUntilIdle()

			viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Unavailable(
				reason = SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				source = HistorySource.WIFI,
			)
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `Cell page unavailability clears the previously rendered actionable card`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val cell = cellHistoryEntry()
			val sourceEntry = SourceAwareHistoryPageEntry.CellOnly(cell)
			val staleCard = DashboardRecentHistoryEntry.CellOnly(
				history = cell,
				detailSelection = requireNotNull(
					SourceHistoryDetailSelection.fromSourceEntry(
						sourceEntry,
						TrackingHistoryReadSnapshot(7L, 11L),
					),
				),
			)
			val failPage = CompletableDeferred<Unit>()
			val repository = QueuedDashboardHistoryRepository(
				successfulHistory(),
				recentFlow = flow {
					emit(listOf(staleCard))
					failPage.await()
					throw DashboardHistoryPageUnavailable(
						reason = SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
						source = HistorySource.CELL,
					)
				},
			)
			val viewModel = createViewModel(repository)
			val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.recentHistory.collect()
			}
			advanceUntilIdle()
			viewModel.recentHistory.value shouldBe
				DashboardRecentHistoryState.Content(listOf(staleCard))

			failPage.complete(Unit)
			advanceUntilIdle()

			viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Unavailable(
				reason = SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
				source = HistorySource.CELL,
			)
			collection.cancel()
		} finally {
			Dispatchers.resetMain()
		}
	}

	@Test
	fun `recent history replay expires after lifecycle stop before resubscription`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val stalePhysical = DashboardRecentHistoryEntry.Physical(trip(77L))
			val repository = QueuedDashboardHistoryRepository(
				successfulHistory(),
				recentFlow = flow {
					emit(listOf(stalePhysical))
					awaitCancellation()
				},
			)
			val viewModel = createViewModel(repository)
			val firstCollection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.recentHistory.collect()
			}
			advanceUntilIdle()
			viewModel.recentHistory.value shouldBe
				DashboardRecentHistoryState.Content(listOf(stalePhysical))

			firstCollection.cancel()
			advanceTimeBy(5_001)
			runCurrent()
			viewModel.recentHistory.value shouldBe DashboardRecentHistoryState.Loading

			val resumedStates = mutableListOf<DashboardRecentHistoryState>()
			val resumedCollection = launch(UnconfinedTestDispatcher(testScheduler)) {
				viewModel.recentHistory.take(2).toList(resumedStates)
			}
			advanceUntilIdle()
			resumedCollection.join()

			resumedStates shouldBe listOf(
				DashboardRecentHistoryState.Loading,
				DashboardRecentHistoryState.Content(listOf(stalePhysical)),
			)
		} finally {
			Dispatchers.resetMain()
		}
	}

	private fun successfulHistory() = DashboardHistory(
		exploration = DashboardHistorySection.Loaded(
			DashboardExplorationHistory(
				totalCells = 12,
				newCellsToday = 3,
				seasonsCovered = 2,
			),
		),
		streak = DashboardHistorySection.Loaded(
			DashboardStreakHistory(
				currentStreak = 4,
				bestStreak = 9,
				weeklyDistances = listOf(1f, 2f, 3f),
				weeklyTrend = DashboardWeeklyTrend.UP,
			),
		),
		latestAchievement = DashboardHistorySection.Loaded(
			DashboardAchievementHistory(
				id = "distance_bronze",
				nameRes = "achievement_distance_bronze",
				tier = AchievementTier.BRONZE,
				unlockedAt = 42L,
			),
		),
	)

	private fun failedOptionalHistory() = DashboardHistory(
		exploration = DashboardHistorySection.Failed,
		streak = DashboardHistorySection.Failed,
		latestAchievement = DashboardHistorySection.Failed,
	)

	private fun trip(id: Long) = Trip(
		id = id,
		startTimeMs = 1_000L,
		endTimeMs = 2_000L,
		distanceM = 100f,
		steps = 50,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 10,
		source = SegmentSource.USER_CREATED,
		createdAt = 1_000L,
	)

	private fun cellHistoryEntry() = CellHistoryEntry(
		key = CellHistoryEntryKey("cell"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(2_000L),
		storedZoneIds = setOf("UTC"),
		state = CellHistoryProductState.MATERIALIZING,
		coverage = CellHistoryCoverage.NONE,
		observations = emptyList(),
		causes = setOf(CellHistoryCause.MATERIALIZATION_BEHIND),
		selection = LocalCellHistorySelection(LocalCellHistoryIdentity("a".repeat(64))),
	)

	private fun TestScope.createViewModel(
		historyRepository: DashboardHistoryRepository,
		isTracking: MutableStateFlow<Boolean> = MutableStateFlow(false),
		collectionHost: MutableStateFlow<Boolean> = isTracking,
		startingGeneration: MutableStateFlow<Long?> = MutableStateFlow(null),
	): DashboardViewModel {
		val layoutRepository = mockk<DashboardLayoutStore>()
		every { layoutRepository.layout } returns flowOf(DashboardLayout())
		val trackerStateReader = mockk<TrackerStateReader>()
		every { trackerStateReader.startingSessionGenerationFlow } returns startingGeneration
		every { trackerStateReader.isServiceRunningFlow } returns collectionHost
		val sessionInfo = MutableStateFlow<TrackerSessionInfo?>(
			if (isTracking.value) {
				TrackerSessionInfo(true)
			} else {
				null
			},
		)
		backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
			isTracking.collect { active ->
				sessionInfo.value = if (active) {
					TrackerSessionInfo(true)
				} else {
					null
				}
			}
		}
		every { trackerStateReader.sessionInfoFlow } returns sessionInfo
		every { trackerStateReader.sessionFlow } returns MutableStateFlow(null)
		val lockManager = mockk<LockManager>()
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		val trackingParamsRepository = mockk<TrackingParamsRepository>()
		every { trackingParamsRepository.data } returns flowOf(TrackingParamsState())

		return DashboardViewModel(
			appContext = ApplicationProvider.getApplicationContext<Context>(),
			historyRepository = historyRepository,
			trackingHistoryRepository = mockk<TrackingHistoryRepository>(),
			layoutRepository = layoutRepository,
			sessionInsightsGenerator = mockk(relaxed = true),
			widgetRegistry = mockk(relaxed = true),
			trackerStateReader = trackerStateReader,
			lockManager = lockManager,
			dailySummaryProvider = mockk(),
			dailyPointsProviderFactory = mockk(),
			goalProgressProviderFactory = mockk(),
			trackingParamsRepository = trackingParamsRepository,
			stepsNumericSummaryRepository = mockk(),
			clock = com.adsamcik.tracker.shared.base.time.FixedClock(),
		)
	}
}

private class QueuedDashboardHistoryRepository(
	vararg histories: DashboardHistory,
	private val recentFlow: Flow<List<DashboardRecentHistoryEntry>> = flowOf(emptyList()),
) : DashboardHistoryRepository {
	private val queue = ArrayDeque(histories.toList())

	override fun observeRecentHistory(): Flow<List<DashboardRecentHistoryEntry>> = recentFlow

	override suspend fun load(): DashboardHistory = queue.removeFirst()
}
