package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerComponentsTest {

	@get:Rule
	val composeRule = createComposeRule()

	// region FocusStartButton

	@Test
	fun focusStartButton_displaysStartText() {
		composeRule.setContent {
			AppTheme {
				FocusStartButton(onClick = {})
			}
		}

		composeRule.onNodeWithText("START").assertIsDisplayed()
	}

	@Test
	fun focusStartButton_clickInvokesCallback() {
		var clicked = false
		composeRule.setContent {
			AppTheme {
				FocusStartButton(onClick = { clicked = true })
			}
		}

		composeRule.onNodeWithText("START").performClick()
		assertTrue("onClick callback should fire", clicked)
	}

	// endregion

	// region FocusMetricsDisplay

	@Test
	fun focusMetricsDisplay_showsDurationAndDistance() {
		composeRule.setContent {
			AppTheme {
				FocusMetricsDisplay(
					durationText = "01:23:45",
					distanceText = "5.2 km"
				)
			}
		}

		composeRule.onNodeWithText("01:23:45").assertIsDisplayed()
		composeRule.onNodeWithText("5.2 km").assertIsDisplayed()
	}

	@Test
	fun focusMetricsDisplay_showsLabels() {
		composeRule.setContent {
			AppTheme {
				FocusMetricsDisplay(
					durationText = "00:00:00",
					distanceText = "0 m"
				)
			}
		}

		// MetricText uppercases labels
		composeRule.onNodeWithText("DURATION").assertIsDisplayed()
		composeRule.onNodeWithText("DISTANCE").assertIsDisplayed()
	}

	// endregion

	// region FocusSignalDisplay

	@Test
	fun focusSignalDisplay_showsWifiAndCellCounts() {
		composeRule.setContent {
			AppTheme {
				FocusSignalDisplay(wifiCount = 7, cellCount = 3)
			}
		}

		composeRule.onNodeWithText("7").assertIsDisplayed()
		composeRule.onNodeWithText("3").assertIsDisplayed()
	}

	@Test
	fun focusSignalDisplay_showsLabels() {
		composeRule.setContent {
			AppTheme {
				FocusSignalDisplay(wifiCount = 0, cellCount = 0)
			}
		}

		// Labels are uppercased in SignalMetric
		composeRule.onNodeWithText("WIFI").assertIsDisplayed()
		composeRule.onNodeWithText("CELL").assertIsDisplayed()
	}

	@Test
	fun focusSignalDisplay_zeroCounts() {
		composeRule.setContent {
			AppTheme {
				FocusSignalDisplay(wifiCount = 0, cellCount = 0)
			}
		}

		// Both wifi and cell show "0"
		composeRule.onAllNodesWithText("0", useUnmergedTree = true).assertCountEquals(2)
	}

	// endregion
}
