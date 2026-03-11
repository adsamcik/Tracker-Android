package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TodayProgressCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withNoSummary_showsNoActivityState() {
		setCardContent(DashboardUiState(dashboardMode = DashboardMode.IDLE, todaySummary = null))

		composeRule.onNodeWithText("Today").assertIsDisplayed()
		composeRule.onNodeWithText("No activity yet today").assertIsDisplayed()
	}

	@Test
	fun withSummary_showsDurationStepsAndTrips() {
		setCardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.IDLE,
				todaySummary = DailySummary(
					totalDistanceM = 2_400f,
					totalSteps = 4_200,
					totalDurationMs = 3_600_000L,
					sessionCount = 3,
				),
			),
		)

		composeRule.onNodeWithText("Duration").assertIsDisplayed()
		composeRule.onNodeWithText("Steps").assertIsDisplayed()
		composeRule.onNodeWithText("Trips").assertIsDisplayed()
	}

	@Test
	fun withSingleSessionAndZeroSteps_hidesOptionalSections() {
		setCardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.IDLE,
				todaySummary = DailySummary(
					totalDistanceM = 800f,
					totalSteps = 0,
					totalDurationMs = 1_200_000L,
					sessionCount = 1,
				),
			),
		)

		composeRule.onAllNodesWithText("Steps").assertCountEquals(0)
		composeRule.onAllNodesWithText("Trips").assertCountEquals(0)
	}

	private fun setCardContent(state: DashboardUiState) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TodayProgressCard(
					state = state,
					onToggleTracking = {},
					onRequestPermission = {},
				)
			}
		}
	}
}
