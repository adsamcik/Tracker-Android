package com.adsamcik.tracker.dashboard.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.data.DashboardAchievementHistory
import com.adsamcik.tracker.dashboard.data.DashboardExplorationHistory
import com.adsamcik.tracker.dashboard.data.DashboardHistory
import com.adsamcik.tracker.dashboard.data.DashboardHistoryRepository
import com.adsamcik.tracker.dashboard.data.DashboardHistorySection
import com.adsamcik.tracker.dashboard.data.DashboardLayout
import com.adsamcik.tracker.dashboard.data.DashboardLayoutStore
import com.adsamcik.tracker.dashboard.data.DashboardStreakHistory
import com.adsamcik.tracker.dashboard.data.DashboardWeeklyTrend
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.stats.api.AchievementTier
import com.adsamcik.tracker.tracker.controller.LockManager
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
	fun `transient optional failures preserve previously loaded cards`() = runTest {
		val mainDispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(mainDispatcher)
		try {
			val repository = QueuedDashboardHistoryRepository(
				successfulHistory(),
				failedOptionalHistory(),
			)
			val viewModel = createViewModel(repository)

			viewModel.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			val exploration = viewModel.explorationState.value
			val streak = viewModel.streakState.value
			val achievement = viewModel.latestAchievement.value

			viewModel.loadHistoricalData(isTracking = false, lastSessionData = null)
			advanceUntilIdle()

			viewModel.explorationState.value shouldBe exploration
			viewModel.streakState.value shouldBe streak
			viewModel.latestAchievement.value shouldBe achievement
		} finally {
			Dispatchers.resetMain()
		}
	}

	private fun successfulHistory() = DashboardHistory(
		lastSession = null,
		recentTrips = emptyList(),
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
		lastSession = null,
		recentTrips = emptyList(),
		exploration = DashboardHistorySection.Failed,
		streak = DashboardHistorySection.Failed,
		latestAchievement = DashboardHistorySection.Failed,
	)

	private fun createViewModel(
		historyRepository: DashboardHistoryRepository,
	): DashboardViewModel {
		val layoutRepository = mockk<DashboardLayoutStore>()
		every { layoutRepository.layout } returns flowOf(DashboardLayout())
		val trackerStateReader = mockk<TrackerStateReader>()
		every { trackerStateReader.isServiceRunningFlow } returns MutableStateFlow(false)
		val lockManager = mockk<LockManager>()
		every { lockManager.isLockedFlow } returns MutableStateFlow(false)
		val trackingParamsRepository = mockk<TrackingParamsRepository>()
		every { trackingParamsRepository.data } returns flowOf(TrackingParamsState())

		return DashboardViewModel(
			appContext = ApplicationProvider.getApplicationContext<Context>(),
			historyRepository = historyRepository,
			layoutRepository = layoutRepository,
			sessionInsightsGenerator = mockk(relaxed = true),
			widgetRegistry = mockk(relaxed = true),
			trackerStateReader = trackerStateReader,
			lockManager = lockManager,
			dailySummaryProvider = mockk(),
			dailyPointsProviderFactory = mockk(),
			goalProgressProviderFactory = mockk(),
			trackingParamsRepository = trackingParamsRepository,
		)
	}
}

private class QueuedDashboardHistoryRepository(
	vararg histories: DashboardHistory,
) : DashboardHistoryRepository {
	private val queue = ArrayDeque(histories.toList())

	override suspend fun load(includeLastSession: Boolean): DashboardHistory =
		queue.removeFirst()
}
