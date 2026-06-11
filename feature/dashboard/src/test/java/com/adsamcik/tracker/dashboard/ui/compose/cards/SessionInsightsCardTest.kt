package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.tracker.insights.InsightCategory
import com.adsamcik.tracker.tracker.insights.SessionInsight
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionInsightsCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyInsights_rendersNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionInsightsCard(insights = emptyList())
			}
		}

		composeRule.onAllNodesWithText("Session Insights").assertCountEquals(0)
	}

	@Test
	fun withInsights_showsTitleAndRows() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionInsightsCard(
					insights = listOf(
						SessionInsight(
							category = InsightCategory.ACHIEVEMENT,
							title = "New record!",
							description = "You walked more today",
							iconRes = android.R.drawable.ic_dialog_info,
						),
						SessionInsight(
							category = InsightCategory.FUN_FACT,
							title = "Fun Fact",
							description = "Distance equals 5 laps",
							iconRes = android.R.drawable.ic_dialog_info,
						),
					),
				)
			}
		}

		composeRule.onNodeWithText("Session Insights").assertIsDisplayed()
		composeRule.onNodeWithText("New record!").assertIsDisplayed()
		composeRule.onNodeWithText("Fun Fact").assertIsDisplayed()
	}
}
