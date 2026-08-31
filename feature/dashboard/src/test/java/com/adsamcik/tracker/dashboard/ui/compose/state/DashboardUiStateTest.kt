package com.adsamcik.tracker.dashboard.ui.compose.state

import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.stats.api.PolicyTier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DashboardUiState")
class DashboardUiStateTest {

	@Nested
	@DisplayName("Default state")
	inner class DefaultStateTest {
		@Test
		fun `default state is EMPTY mode`() {
			val state = DashboardUiState()
			state.dashboardMode shouldBe DashboardMode.EMPTY
		}

		@Test
		fun `default state is not tracking`() {
			val state = DashboardUiState()
			state.isTracking shouldBe false
		}

		@Test
		fun `default state has no location permission`() {
			val state = DashboardUiState()
			state.hasLocationPermission shouldBe false
		}

		@Test
		fun `default policy tier is OFF`() {
			val state = DashboardUiState()
			state.policyTier shouldBe PolicyTier.OFF
		}

		@Test
		fun `default recent history is loading`() {
			val state = DashboardUiState()
			state.recentHistory shouldBe DashboardRecentHistoryState.Loading
		}

		@Test
		fun `default points today is zero`() {
			val state = DashboardUiState()
			state.pointsToday shouldBe 0
		}
	}

	@Nested
	@DisplayName("previewIdle factory")
	inner class PreviewIdleTest {
		@Test
		fun `previewIdle sets IDLE mode`() {
			val state = DashboardUiState.previewIdle()
			state.dashboardMode shouldBe DashboardMode.IDLE
		}

		@Test
		fun `previewIdle is not tracking`() {
			val state = DashboardUiState.previewIdle()
			state.isTracking shouldBe false
		}

		@Test
		fun `previewIdle has location permission`() {
			val state = DashboardUiState.previewIdle()
			state.hasLocationPermission shouldBe true
		}

		@Test
		fun `previewIdle has gamification data`() {
			val state = DashboardUiState.previewIdle()
			state.pointsToday shouldBe 42
			state.goalProgress.gamificationEnabled shouldBe true
			state.goalProgress.dailySteps shouldBe 6500
			state.goalProgress.dailyGoalSteps shouldBe 10000
			state.goalProgress.dailyProgress shouldBe 0.65f
		}

		@Test
		fun `previewIdle has streak data`() {
			val state = DashboardUiState.previewIdle()
			state.streakState.currentStreak shouldBe 3
			state.streakState.bestStreak shouldBe 7
			state.streakState.weeklyDistances shouldHaveSize 7
			state.streakState.weeklyTrend shouldBe WeeklyTrend.UP
		}

		@Test
		fun `previewIdle has exploration data`() {
			val state = DashboardUiState.previewIdle()
			state.explorationState.hasExplorationData shouldBe true
			state.explorationState.newCellsToday shouldBe 5
			state.explorationState.totalCells shouldBe 128
		}
	}

	@Nested
	@DisplayName("previewTracking factory")
	inner class PreviewTrackingTest {
		@Test
		fun `previewTracking sets TRACKING mode`() {
			val state = DashboardUiState.previewTracking()
			state.dashboardMode shouldBe DashboardMode.TRACKING
		}

		@Test
		fun `previewTracking is tracking`() {
			val state = DashboardUiState.previewTracking()
			state.isTracking shouldBe true
		}

		@Test
		fun `previewTracking has PRECISION policy tier`() {
			val state = DashboardUiState.previewTracking()
			state.policyTier shouldBe PolicyTier.PRECISION
		}

		@Test
		fun `previewTracking has location permission`() {
			val state = DashboardUiState.previewTracking()
			state.hasLocationPermission shouldBe true
		}

		@Test
		fun `previewTracking has daily goal progress`() {
			val state = DashboardUiState.previewTracking()
			state.goalProgress.dailySteps shouldBe 2100
			state.goalProgress.dailyProgress shouldBe 0.21f
		}
	}

	@Nested
	@DisplayName("GoalProgressState")
	inner class GoalProgressTest {
		@Test
		fun `default goal progress has zeroes`() {
			val progress = GoalProgressState()
			progress.gamificationEnabled shouldBe false
			progress.dailySteps shouldBe 0
			progress.dailyProgress shouldBe 0f
			progress.weeklySteps shouldBe 0
			progress.weeklyProgress shouldBe 0f
		}
	}

	@Nested
	@DisplayName("StreakState")
	inner class StreakStateTest {
		@Test
		fun `default streak state is zeroed`() {
			val streak = StreakState()
			streak.currentStreak shouldBe 0
			streak.bestStreak shouldBe 0
			streak.weeklyDistances.shouldBeEmpty()
			streak.weeklyTrend shouldBe WeeklyTrend.STEADY
		}
	}

	@Nested
	@DisplayName("ExplorationUiState")
	inner class ExplorationUiStateTest {
		@Test
		fun `default exploration state has no data`() {
			val exploration = ExplorationUiState()
			exploration.hasExplorationData shouldBe false
			exploration.newCellsToday shouldBe 0
			exploration.totalCells shouldBe 0
			exploration.seasonsCovered shouldBe 0
		}
	}
}
