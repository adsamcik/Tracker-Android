package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class TrackerDashboardTrackingComponentsTest {

	@get:Rule
	val composeRule = createComposeRule()

	// region ComponentCard - Enabled with metrics

	@Test
	fun componentCard_locationEnabled_withMetrics_showsTitle() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Location,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(
							label = "Accuracy",
							value = "±5 m"
						),
						secondary = emptyList()
					),
					onClick = null
				)
			}
		}

		composeRule.onNodeWithTag("component_card_location").assertIsDisplayed()
	}

	@Test
	fun componentCard_locationEnabled_showsPrimaryMetric() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Location,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(
							label = "Accuracy",
							value = "±5 m"
						),
						secondary = emptyList()
					),
					onClick = null
				)
			}
		}

		composeRule.onNodeWithText("Accuracy").assertIsDisplayed()
		composeRule.onNodeWithText("±5 m").assertIsDisplayed()
	}

	@Test
	fun componentCard_locationEnabled_showsSecondaryMetrics() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Location,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(
							label = "Accuracy",
							value = "±5 m"
						),
						secondary = listOf(
							ComponentMetric(
								label = "Speed",
								value = "12 km/h"
							)
						)
					),
					onClick = null
				)
			}
		}

		composeRule.onNodeWithText("Speed").assertIsDisplayed()
		composeRule.onNodeWithText("12 km/h").assertIsDisplayed()
	}

	// endregion

	// region ComponentCard - Disabled

	@Test
	fun componentCard_disabled_showsSettingsIcon() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Wifi,
					enabled = false,
					metrics = null,
					onClick = {}
				)
			}
		}

		composeRule.onNodeWithTag("component_card_wifi").assertIsDisplayed()
		composeRule.onNodeWithTag("component_settings_icon", useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun componentCard_disabled_clickInvokesCallback() {
		var clicked = false
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Cell,
					enabled = false,
					metrics = null,
					onClick = { clicked = true }
				)
			}
		}

		composeRule.onNodeWithTag("component_card_cell").performClick()
		assertTrue("Disabled card click should invoke settings callback", clicked)
	}

	// endregion

	// region ComponentCard - Enabled without metrics (waiting)

	@Test
	fun componentCard_enabledNoMetrics_showsWaitingState() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Activity,
					enabled = true,
					metrics = null,
					onClick = null
				)
			}
		}

		composeRule.onNodeWithTag("component_card_activity").assertIsDisplayed()
		// No settings icon when enabled
		composeRule.onNodeWithTag("component_settings_icon").assertDoesNotExist()
	}

	// endregion

	// region ComponentCard - All component types render

	@Test
	fun componentCard_locationRenders() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Location,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(label = "Test", value = "Value"),
						secondary = emptyList()
					),
					onClick = null
				)
			}
		}
		composeRule.onNodeWithTag("component_card_location").assertIsDisplayed()
	}

	@Test
	fun componentCard_wifiRenders() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Wifi,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(label = "Test", value = "Value"),
						secondary = emptyList()
					),
					onClick = null
				)
			}
		}
		composeRule.onNodeWithTag("component_card_wifi").assertIsDisplayed()
	}

	@Test
	fun componentCard_cellRenders() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Cell,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(label = "Test", value = "Value"),
						secondary = emptyList()
					),
					onClick = null
				)
			}
		}
		composeRule.onNodeWithTag("component_card_cell").assertIsDisplayed()
	}

	@Test
	fun componentCard_activityRenders() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Activity,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(label = "Test", value = "Value"),
						secondary = emptyList()
					),
					onClick = null
				)
			}
		}
		composeRule.onNodeWithTag("component_card_activity").assertIsDisplayed()
	}

	// endregion

	// region ComponentMetricText

	@Test
	fun componentMetricText_showsLabelAndValue() {
		composeRule.setContent {
			AppTheme {
				ComponentMetricText(
					metric = ComponentMetric(
						label = "Altitude",
						value = "350 m"
					),
					emphasize = true
				)
			}
		}

		composeRule.onNodeWithText("Altitude").assertIsDisplayed()
		composeRule.onNodeWithText("350 m").assertIsDisplayed()
	}

	@Test
	fun componentMetricText_nonEmphasized_showsLabelAndValue() {
		composeRule.setContent {
			AppTheme {
				ComponentMetricText(
					metric = ComponentMetric(
						label = "Confidence",
						value = "85%"
					),
					emphasize = false
				)
			}
		}

		composeRule.onNodeWithText("Confidence").assertIsDisplayed()
		composeRule.onNodeWithText("85%").assertIsDisplayed()
	}

	@Test
	fun componentMetricText_withStatus_rendersStatus() {
		composeRule.setContent {
			AppTheme {
				ComponentCard(
					component = TrackingComponent.Location,
					enabled = true,
					metrics = ComponentMetrics(
						primary = ComponentMetric(label = "Accuracy", value = "±3 m"),
						secondary = emptyList(),
						status = "Updated 5 sec ago"
					),
					onClick = null
				)
			}
		}

		composeRule.onNodeWithText("Updated 5 sec ago").assertIsDisplayed()
	}

	// endregion
}
