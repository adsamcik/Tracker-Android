package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassMetricCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun basicRendering_showsLabelAndValue() {
		setContent(label = "Distance", value = "5.2", unit = null, trend = null)

		composeRule.onNodeWithText("Distance").assertIsDisplayed()
		composeRule.onNodeWithText("5.2").assertIsDisplayed()
	}

	@Test
	fun withUnit_showsUnitSuffix() {
		setContent(label = "Speed", value = "12", unit = "km/h", trend = null)

		composeRule.onNodeWithText("12").assertIsDisplayed()
		composeRule.onNodeWithText("km/h").assertIsDisplayed()
	}

	@Test
	fun withPositiveTrend_showsUpArrow() {
		setContent(label = "Steps", value = "4200", unit = null, trend = 15f)

		composeRule.onAllNodesWithContentDescription("Trending up").assertCountEquals(1)
	}

	@Test
	fun withNegativeTrend_showsDownArrow() {
		setContent(label = "Steps", value = "2000", unit = null, trend = -10f)

		composeRule.onAllNodesWithContentDescription("Trending down").assertCountEquals(1)
	}

	@Test
	fun nullTrend_noTrendArrow() {
		setContent(label = "Steps", value = "3000", unit = null, trend = null)

		composeRule.onAllNodesWithContentDescription("Trending up").assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription("Trending down").assertCountEquals(0)
	}

	@Test
	fun zeroTrend_noTrendArrow() {
		setContent(label = "Steps", value = "3000", unit = null, trend = 0f)

		composeRule.onAllNodesWithContentDescription("Trending up").assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription("Trending down").assertCountEquals(0)
	}

	private fun setContent(label: String, value: String, unit: String?, trend: Float?) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassMetricCard(
					icon = Icons.Default.DirectionsWalk,
					label = label,
					value = value,
					unit = unit,
					trend = trend,
				)
			}
		}
	}
}
