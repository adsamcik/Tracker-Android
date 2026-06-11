package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AltitudeSparklineTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withEmptyList_showsNoAltitudeDataText() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AltitudeSparkline(
					altitudeHistory = emptyList(),
					currentAltitude = null,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		composeRule.onNodeWithText("No altitude data").assertIsDisplayed()
	}

	@Test
	fun withEmptyList_hasNoAltitudeDataContentDescription() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AltitudeSparkline(
					altitudeHistory = emptyList(),
					currentAltitude = null,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// The content description is set on the Canvas via semantics
		composeRule.onAllNodesWithContentDescription(
			"No altitude data",
			substring = true,
		).assertCountEquals(1)
	}

	@Test
	fun withPopulatedData_rendersCanvasWithAltitudeRange() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AltitudeSparkline(
					altitudeHistory = listOf(100f, 150f, 200f, 180f, 250f),
					currentAltitude = 250f,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// With data, "No altitude data" should NOT be shown
		composeRule.onAllNodesWithText("No altitude data").assertCountEquals(0)
	}

	@Test
	fun withSinglePoint_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				AltitudeSparkline(
					altitudeHistory = listOf(150f),
					currentAltitude = 150f,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// Single point still renders the Canvas (but returns early inside Canvas block)
		composeRule.onAllNodesWithText("No altitude data").assertCountEquals(0)
	}
}
