package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedSparklineTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withEmptyList_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SpeedSparkline(
					speedHistory = emptyList(),
					currentSpeed = null,
					maxSpeed = null,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// SpeedSparkline returns early when list is empty, so nothing is rendered
		composeRule.onAllNodesWithContentDescription(
			"speed",
			substring = true,
		).assertCountEquals(0)
	}

	@Test
	fun withData_rendersCanvasWithContentDescription() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SpeedSparkline(
					speedHistory = listOf(5f, 10f, 15f, 12f, 8f),
					currentSpeed = 8f,
					maxSpeed = 15f,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// Canvas has contentDescription with the number of points
		composeRule.onAllNodesWithContentDescription(
			"5",
			substring = true,
		).assertCountEquals(1)
	}

	@Test
	fun withSinglePoint_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SpeedSparkline(
					speedHistory = listOf(10f),
					currentSpeed = 10f,
					maxSpeed = 10f,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// Single point: Canvas is rendered but drawing returns early (< 2 points)
		// Should not crash
		composeRule.onAllNodesWithContentDescription(
			"1",
			substring = true,
		).assertCountEquals(1)
	}

	@Test
	fun withNullCurrentAndMaxSpeed_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SpeedSparkline(
					speedHistory = listOf(3f, 7f, 2f),
					currentSpeed = null,
					maxSpeed = null,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		composeRule.onAllNodesWithContentDescription(
			"3",
			substring = true,
		).assertCountEquals(1)
	}
}
