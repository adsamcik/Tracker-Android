package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [SummaryRouteContent].
 *
 * The Hilt-backed [SummaryRoute] entry composable is exercised here via its
 * stateless `SummaryRouteContent` body. That keeps the test surface narrow:
 * the route only adds a `LaunchedEffect(loadSummaryStats)` and a
 * `viewModel.summaryStatsState.collectAsState()` wiring on top of the body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryRouteComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun navigateBackLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.action_navigate_back)

	private fun summaryTitle(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_sum_title)

	private fun summaryEmptyTitle(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_summary_empty)

	private fun summaryLoadingLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_summary_loading)

	private fun summaryErrorTitle(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_summary_error)

	// ─── Chrome ──────────────────────────────────────────────────────────

	@Test
	fun `route renders scaffold with summaryRoute testTag and title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(state = StatsLoadState.Idle, onBack = {})
			}
		}
		composeTestRule.onNodeWithTag("summaryRoute").assertIsDisplayed()
		composeTestRule.onNodeWithText(summaryTitle()).assertIsDisplayed()
	}

	// ─── Loading / Idle ──────────────────────────────────────────────────

	@Test
	fun `Idle state shows loading copy`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(state = StatsLoadState.Idle, onBack = {})
			}
		}
		composeTestRule.onNodeWithText(summaryLoadingLabel()).assertIsDisplayed()
	}

	@Test
	fun `Loading state shows loading copy`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(state = StatsLoadState.Loading, onBack = {})
			}
		}
		composeTestRule.onNodeWithText(summaryLoadingLabel()).assertIsDisplayed()
	}

	// ─── Empty ───────────────────────────────────────────────────────────

	@Test
	fun `Success with empty list shows empty state title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(
					state = StatsLoadState.Success(emptyList()),
					onBack = {},
				)
			}
		}
		composeTestRule.onNodeWithText(summaryEmptyTitle()).assertIsDisplayed()
	}

	// ─── Success ─────────────────────────────────────────────────────────

	@Test
	fun `Success with stats renders each row value`() {
		val stats = listOf(
			Stat(
				nameRes = R.string.stats_time,
				iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_access_time_24px,
				displayType = StatisticDisplayType.INFORMATION,
				data = "42 km",
			),
			Stat(
				nameRes = R.string.stats_session_count,
				iconRes = com.adsamcik.tracker.shared.base.R.drawable.ic_outline_map_24dp,
				displayType = StatisticDisplayType.INFORMATION,
				data = "7",
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(
					state = StatsLoadState.Success(stats),
					onBack = {},
				)
			}
		}
		composeTestRule.onNodeWithText("42 km").assertIsDisplayed()
		composeTestRule.onNodeWithText("7").assertIsDisplayed()
	}

	// ─── Error ───────────────────────────────────────────────────────────

	@Test
	fun `Error state shows title and message`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(
					state = StatsLoadState.Error("Database offline"),
					onBack = {},
				)
			}
		}
		composeTestRule.onNodeWithText(summaryErrorTitle()).assertIsDisplayed()
		composeTestRule.onNodeWithText("Database offline").assertIsDisplayed()
	}

	// ─── Back affordance ─────────────────────────────────────────────────

	@Test
	fun `tapping back navigation icon invokes onBack`() {
		var backCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryRouteContent(
					state = StatsLoadState.Idle,
					onBack = { backCount++ },
				)
			}
		}
		val backNode = composeTestRule.onNodeWithContentDescription(navigateBackLabel())
		backNode.assertHasClickAction()
		backNode.performClick()
		assert(backCount == 1) { "Expected onBack to be invoked exactly once, was $backCount" }
	}
}
