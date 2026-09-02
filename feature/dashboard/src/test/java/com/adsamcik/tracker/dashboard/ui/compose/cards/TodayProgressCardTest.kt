package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.base.di.DailySummary
import com.adsamcik.tracker.shared.base.di.QualifiedStepCount
import com.adsamcik.tracker.shared.base.di.QualifiedStepCountUnavailableReason
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
				goalProgress = GoalProgressState(
					dailySteps = QualifiedStepCount.Ready(4_200),
				),
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
	fun withLegacyStepsButUnavailableQualifiedSteps_hidesStepNumber() {
		setCardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.IDLE,
				goalProgress = GoalProgressState(
					dailySteps = QualifiedStepCount.Unavailable(
						QualifiedStepCountUnavailableReason.STORAGE_UNAVAILABLE,
					),
				),
				todaySummary = DailySummary(
					totalDistanceM = 800f,
					totalSteps = 4_200,
					totalDurationMs = 1_200_000L,
					sessionCount = 1,
				),
			),
		)

		composeRule.onAllNodesWithText("Steps").assertCountEquals(0)
		composeRule.onAllNodesWithText("4,200").assertCountEquals(0)
	}

	@Test
	fun withQualifiedStepsAndNoLegacySummary_showsOnlyTruthfulSteps() {
		setCardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.IDLE,
				goalProgress = GoalProgressState(
					dailySteps = QualifiedStepCount.Ready(4_200),
				),
				todaySummary = null,
			),
		)

		composeRule.onNodeWithText("Steps").assertIsDisplayed()
		composeRule.onNodeWithText("4,200").assertIsDisplayed()
		composeRule.onAllNodesWithText("No activity yet today").assertCountEquals(0)
		composeRule.onAllNodesWithText("Distance").assertCountEquals(0)
		composeRule.onAllNodesWithText("Duration").assertCountEquals(0)
		composeRule.onAllNodesWithText("Trips").assertCountEquals(0)
	}

	@Test
	fun withQualifiedZeroAndNoLegacySummary_showsTruthfulZeroSteps() {
		setCardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.IDLE,
				goalProgress = GoalProgressState(
					dailySteps = QualifiedStepCount.Ready(0),
				),
				todaySummary = null,
			),
		)

		composeRule.onNodeWithText("Steps").assertIsDisplayed()
		composeRule.onNodeWithText("0").assertIsDisplayed()
		composeRule.onAllNodesWithText("No activity yet today").assertCountEquals(0)
		composeRule.onAllNodesWithText("Distance").assertCountEquals(0)
		composeRule.onAllNodesWithText("Duration").assertCountEquals(0)
	}

	@Test
	fun withUnavailableStepsAndRawStepsOnlySummary_showsNoActivity() {
		setCardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.IDLE,
				goalProgress = GoalProgressState(
					dailySteps = QualifiedStepCount.Unavailable(
						QualifiedStepCountUnavailableReason.SOURCE_EVIDENCE_UNAVAILABLE,
					),
				),
				todaySummary = DailySummary(
					totalDistanceM = 0f,
					totalSteps = 4_200,
					totalDurationMs = 0L,
					sessionCount = 0,
				),
			),
		)

		composeRule.onNodeWithText("No activity yet today").assertIsDisplayed()
		composeRule.onAllNodesWithText("Steps").assertCountEquals(0)
		composeRule.onAllNodesWithText("4,200").assertCountEquals(0)
		composeRule.onAllNodesWithText("Distance").assertCountEquals(0)
		composeRule.onAllNodesWithText("Duration").assertCountEquals(0)
	}

	@Test
	fun withSingleSessionAndMissingQualifiedSteps_hidesOptionalSections() {
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
