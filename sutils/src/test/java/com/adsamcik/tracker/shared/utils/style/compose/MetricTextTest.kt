package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetricTextTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun displaysValueAndLabel() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MetricText(value = "42", label = "steps")
			}
		}
		composeRule.onNodeWithText("42").assertIsDisplayed()
		composeRule.onNodeWithText("STEPS").assertIsDisplayed()
	}

	@Test
	fun labelIsUppercased() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MetricText(value = "100", label = "distance")
			}
		}
		composeRule.onNodeWithText("DISTANCE").assertIsDisplayed()
	}

	@Test
	fun largeValue_displaysWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MetricText(value = "999999", label = "points")
			}
		}
		composeRule.onNodeWithText("999999").assertIsDisplayed()
	}

	@Test
	fun emptyValue_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MetricText(value = "", label = "empty")
			}
		}
		composeRule.onNodeWithText("EMPTY").assertIsDisplayed()
	}

	@Test
	fun customModifier_isApplied() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MetricText(
					value = "7",
					label = "km",
					modifier = Modifier.testTag("metric"),
				)
			}
		}
		composeRule.onNodeWithTag("metric").assertIsDisplayed()
	}

	@Test
	fun decimalValue_displaysCorrectly() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MetricText(value = "3.14", label = "km/h")
			}
		}
		composeRule.onNodeWithText("3.14").assertIsDisplayed()
		composeRule.onNodeWithText("KM/H").assertIsDisplayed()
	}
}
