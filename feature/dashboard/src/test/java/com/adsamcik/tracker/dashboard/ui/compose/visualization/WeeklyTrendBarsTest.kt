package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyTrendBarsTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun rendersSevenDayLabels() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyTrendBars(
					dailyValues = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f),
					labels = listOf("M", "T", "W", "T", "F", "S", "S"),
					highlightIndex = 0,
				)
			}
		}

		composeRule.onNodeWithText("M").assertIsDisplayed()
		composeRule.onNodeWithText("W").assertIsDisplayed()
		composeRule.onNodeWithText("F").assertIsDisplayed()
	}

	@Test(expected = IllegalArgumentException::class)
	fun wrongSize_throws() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				WeeklyTrendBars(
					dailyValues = listOf(1f, 2f, 3f),
					labels = listOf("M", "T", "W"),
					highlightIndex = null,
				)
			}
		}
	}
}
