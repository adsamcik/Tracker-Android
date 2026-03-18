package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.dashboard.ui.compose.state.WeeklyTrend
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreakBannerTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withNoActiveStreak_hidesTrendEvenWhenWeeklyDataExists() {
		setBannerContent(
			StreakState(
				currentStreak = 0,
				weeklyDistances = listOf(0f, 0f, 0f, 0.01f, 3f, 3f, 3f),
				weeklyTrend = WeeklyTrend.UP,
			),
		)

		composeRule.onNodeWithText("Start a streak!").assertIsDisplayed()
		composeRule.onAllNodesWithText("↑999%").assertCountEquals(0)
	}

	@Test
	fun withActiveStreak_showsTrendMetrics() {
		setBannerContent(
			StreakState(
				currentStreak = 4,
				weeklyDistances = listOf(0f, 0f, 0f, 0.01f, 3f, 3f, 3f),
				weeklyTrend = WeeklyTrend.UP,
			),
		)

		composeRule.onNodeWithText("4").assertIsDisplayed()
		composeRule.onNodeWithText("day streak").assertIsDisplayed()
		composeRule.onNodeWithText("↑999%").assertIsDisplayed()
	}

	private fun setBannerContent(state: StreakState) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				StreakBanner(streakState = state)
			}
		}
	}
}
