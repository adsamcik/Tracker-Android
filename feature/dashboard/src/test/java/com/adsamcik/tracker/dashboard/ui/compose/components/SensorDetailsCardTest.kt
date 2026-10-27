package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveActivityValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureMetrics
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLivePressureValue
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensorDetailsCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun savedNativeSensors_renderWithoutLegacyCollectionData() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(collectionSnapshot = null, isTracking = true,
					presentation = DashboardLiveSessionPresentation.Standard(1L,
						activity = DashboardLiveActivityValue(ActivityHistoryProductState.MATERIALIZING,
							ActivityHistoryCoverage.NONE, null, ActivityHistoryType.STILL, 0, true, 1_500L),
						pressure = DashboardLivePressureValue.Partial(DashboardLivePressureMetrics(
							1001.5f, 999.5f, 1002f, 1.5f, PressureHistoryCoverage.PARTIAL, listOf("UTC")))))
			}
		}
		composeRule.onNodeWithText("Sensor Details").performClick()
		composeRule.onNodeWithText("Still").assertIsDisplayed()
		composeRule.onNodeWithText("1001.5 hPa").assertIsDisplayed()
		composeRule.onNodeWithText("%", substring = true).assertDoesNotExist()
	}

	@Test
	fun withNotTracking_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(
					collectionSnapshot = null,
					isTracking = false,
				)
			}
		}

		composeRule.onAllNodesWithText("Sensor Details").assertCountEquals(0)
	}

	@Test
	fun withNullCollectionSnapshot_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(
					collectionSnapshot = null,
					isTracking = true,
				)
			}
		}

		// Early return when collectionSnapshot is null
		composeRule.onAllNodesWithText("Sensor Details").assertCountEquals(0)
	}

	@Test
	fun withTrackingAndNotTracking_consistentEarlyReturn() {
		// Both conditions must be true for card to render
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(
					collectionSnapshot = null,
					isTracking = false,
					isDebugBuild = false,
				)
			}
		}

		composeRule.onAllNodesWithText("Sensor Details").assertCountEquals(0)
	}
}
