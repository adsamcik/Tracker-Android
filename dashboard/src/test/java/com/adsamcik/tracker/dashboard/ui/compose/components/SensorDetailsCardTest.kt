package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
	fun withNotTracking_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(
					collectionData = null,
					isTracking = false,
				)
			}
		}

		composeRule.onAllNodesWithText("Sensor Details").assertCountEquals(0)
	}

	@Test
	fun withNullCollectionData_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(
					collectionData = null,
					isTracking = true,
				)
			}
		}

		// Early return when collectionData is null
		composeRule.onAllNodesWithText("Sensor Details").assertCountEquals(0)
	}

	@Test
	fun withTrackingAndNotTracking_consistentEarlyReturn() {
		// Both conditions must be true for card to render
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SensorDetailsCard(
					collectionData = null,
					isTracking = false,
					isDebugBuild = false,
				)
			}
		}

		composeRule.onAllNodesWithText("Sensor Details").assertCountEquals(0)
	}
}
