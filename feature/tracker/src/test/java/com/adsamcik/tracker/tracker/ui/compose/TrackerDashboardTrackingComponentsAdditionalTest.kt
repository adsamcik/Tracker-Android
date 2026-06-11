package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Additional tests for ComponentCard and ComponentMetricText covering:
 * - Multiple secondary metrics
 * - Copyable metric with onCopy (content description check)
 * - Null primary metric
 * - Disabled card with null onClick
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardTrackingComponentsAdditionalTest {

	@get:Rule
	val composeRule = createComposeRule()

	// region ComponentCard - multiple secondary metrics

	@Test
	fun componentCard_multipleSecondaryMetrics_displaysAll() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Location,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(
							label = "Accuracy",
							value = "±3 m",
						),
						secondary = listOf(
							ComponentMetric(label = "Speed", value = "5.2 km/h"),
							ComponentMetric(label = "Altitude", value = "285 m"),
							ComponentMetric(label = "Bearing", value = "N 15°"),
						),
					),
					onClick = null,
				)
			}
		}

		composeRule.onNodeWithText("Accuracy").assertIsDisplayed()
		composeRule.onNodeWithText("±3 m").assertIsDisplayed()
		composeRule.onNodeWithText("Speed").assertIsDisplayed()
		composeRule.onNodeWithText("5.2 km/h").assertIsDisplayed()
		composeRule.onNodeWithText("Altitude").assertIsDisplayed()
		composeRule.onNodeWithText("285 m").assertIsDisplayed()
		composeRule.onNodeWithText("Bearing").assertIsDisplayed()
		composeRule.onNodeWithText("N 15°").assertIsDisplayed()
	}

	// endregion

	// region ComponentCard - null primary, with secondary

	@Test
	fun componentCard_nullPrimary_showsOnlySecondary() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Wifi,
					enabled = true,
					metrics = ComponentMetrics(
						primary = null,
						secondary = listOf(
							ComponentMetric(label = "Networks", value = "12"),
						),
					),
					onClick = null,
				)
			}
		}

		composeRule.onNodeWithTag("component_card_wifi").assertIsDisplayed()
		composeRule.onNodeWithText("Networks").assertIsDisplayed()
		composeRule.onNodeWithText("12").assertIsDisplayed()
	}

	// endregion

	// region ComponentCard - disabled with null onClick

	@Test
	fun componentCard_disabledNullOnClick_showsDisabledText() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Cell,
					enabled = false,
					metrics = null,
					onClick = null,
				)
			}
		}

		composeRule.onNodeWithTag("component_card_cell").assertIsDisplayed()
		composeRule.onNodeWithText("Disabled", substring = true).assertIsDisplayed()
	}

	// endregion

	// region ComponentMetricText - with copyable value

	@Test
	fun componentMetricText_withCopyableValue_showsCopyIcon() {
		composeRule.setContent {
			AppTheme {
				ComponentMetricText(
					metric = ComponentMetric(
						label = "Coordinates",
						value = "50.0851° N, 14.4208° E",
						copyableValue = "50.0851, 14.4208",
					),
					emphasize = true,
					onCopy = {},
				)
			}
		}

		composeRule.onNodeWithText("Coordinates").assertIsDisplayed()
		composeRule.onNodeWithText("50.0851° N, 14.4208° E").assertIsDisplayed()
		// Copy icon should be present (content description "Copy")
		composeRule.onNodeWithContentDescription("Copy", substring = true).assertIsDisplayed()
	}

	@Test
	fun componentMetricText_withoutCopyableValue_noCopyIcon() {
		composeRule.setContent {
			AppTheme {
				ComponentMetricText(
					metric = ComponentMetric(
						label = "Speed",
						value = "12 km/h",
					),
					emphasize = false,
					onCopy = null,
				)
			}
		}

		composeRule.onNodeWithText("Speed").assertIsDisplayed()
		composeRule.onNodeWithText("12 km/h").assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Copy", substring = true).assertDoesNotExist()
	}

	// endregion

	// region ComponentMetricText - semantics

	@Test
	fun componentMetricText_hasCorrectContentDescription() {
		composeRule.setContent {
			AppTheme {
				ComponentMetricText(
					metric = ComponentMetric(
						label = "Accuracy",
						value = "±5 m",
					),
					emphasize = true,
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			"Accuracy: ±5 m",
			substring = false,
		).assertIsDisplayed()
	}

	// endregion

	// region ComponentCard - empty secondary list with primary

	@Test
	fun componentCard_emptySecondaryList_showsOnlyPrimary() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Activity,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(
							label = "Activity",
							value = "Walking",
						),
						secondary = emptyList(),
					),
					onClick = null,
				)
			}
		}

		composeRule.onNodeWithTag("component_card_activity").assertIsDisplayed()
		composeRule.onNodeWithText("Activity").assertIsDisplayed()
		composeRule.onNodeWithText("Walking").assertIsDisplayed()
	}

	// endregion
}
