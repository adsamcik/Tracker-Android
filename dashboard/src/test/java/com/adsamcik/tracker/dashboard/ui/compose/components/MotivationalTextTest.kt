package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.StreakState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MotivationalTextTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun idleState_showsTimeBasedGreeting() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MotivationalText(
					state = DashboardUiState(dashboardMode = DashboardMode.IDLE),
				)
			}
		}

		// One of the time-of-day greetings should be displayed depending on current hour
		val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
		val expected = when (hour) {
			in 6..11 -> "Good morning"
			in 12..16 -> "Good afternoon"
			in 17..21 -> "Good evening"
			else -> "Night owl"
		}
		composeRule.onNodeWithText(expected, substring = true).assertExists()
	}

	@Test
	fun withStreakGreaterThanOne_showsStreakSuffix() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MotivationalText(
					state = DashboardUiState(
						dashboardMode = DashboardMode.IDLE,
						streakState = StreakState(currentStreak = 5),
					),
				)
			}
		}

		composeRule.onNodeWithText("streak", substring = true).assertExists()
	}
}
