package com.adsamcik.tracker.dashboard.ui.compose.visualization

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPathPreviewTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun makeLocation(lat: Double, lon: Double) = Location(
		time = System.currentTimeMillis(),
		latitude = lat,
		longitude = lon,
		altitude = null,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = null,
		speedAccuracy = null,
	)

	@Test
	fun withTwoOrMorePoints_rendersCanvasWithContentDescription() {
		val points = listOf(
			makeLocation(50.0, 14.0),
			makeLocation(50.001, 14.001),
			makeLocation(50.002, 14.002),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionPathPreview(
					points = points,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// Canvas has contentDescription referencing point count
		composeRule.onAllNodesWithContentDescription(
			"3",
			substring = true,
		).assertCountEquals(1)
	}

	@Test
	fun withSinglePoint_rendersWithoutCrash() {
		val points = listOf(makeLocation(50.0, 14.0))

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionPathPreview(
					points = points,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// Canvas is rendered but returns early inside (< 2 points)
		composeRule.onAllNodesWithContentDescription(
			"1",
			substring = true,
		).assertCountEquals(1)
	}

	@Test
	fun withEmptyList_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionPathPreview(
					points = emptyList(),
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		// Empty list renders Canvas but draws nothing
		composeRule.onAllNodesWithContentDescription(
			"0",
			substring = true,
		).assertCountEquals(1)
	}
}
