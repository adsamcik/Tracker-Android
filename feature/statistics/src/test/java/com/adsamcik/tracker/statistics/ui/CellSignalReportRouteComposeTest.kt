package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.viewmodel.CellSignalReportLoadState
import com.adsamcik.tracker.stats.api.repository.CellSignalReport
import com.adsamcik.tracker.stats.api.repository.CellTowerStat
import com.adsamcik.tracker.stats.api.repository.NetworkTypeSignalStat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [CellSignalReportRouteContent], exercised via the stateless body (same
 * pattern as [WifiStatsRouteComposeTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CellSignalReportRouteComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

	@Test
	fun `route renders scaffold with testTag and title`() {
		setContent(CellSignalReportLoadState.Idle)
		composeTestRule.onNodeWithTag("cellSignalReportRoute").assertIsDisplayed()
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_dialog_title)).assertIsDisplayed()
	}

	@Test
	fun `Loading state shows loading copy`() {
		setContent(CellSignalReportLoadState.Loading)
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_dialog_loading)).assertIsDisplayed()
	}

	@Test
	fun `Empty state shows empty title`() {
		setContent(CellSignalReportLoadState.Empty)
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_dialog_empty_title)).assertIsDisplayed()
	}

	@Test
	fun `Success renders overview and section titles`() {
		val report = CellSignalReport(
			totalSamples = 1234L,
			distinctTowers = 12L,
			networkTypes = listOf(NetworkTypeSignalStat(4, 1000L, 81.0, 72.0, 8L)),
			topTowers = listOf(CellTowerStat(555L, 230, 1, 4, 400L, 65.0)),
		)
		setContent(CellSignalReportLoadState.Success(report))

		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_total_samples)).assertIsDisplayed()
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_distinct_towers)).assertIsDisplayed()
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_section_networks)).assertIsDisplayed()
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_section_towers)).assertIsDisplayed()
	}

	@Test
	fun `Error state shows title and message`() {
		setContent(CellSignalReportLoadState.Error("Cell repository unavailable"))
		composeTestRule.onNodeWithText(string(R.string.stats_cell_signal_dialog_error)).assertIsDisplayed()
		composeTestRule.onNodeWithText("Cell repository unavailable").assertIsDisplayed()
	}

	@Test
	fun `tapping back navigation icon invokes onBack`() {
		var backCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CellSignalReportRouteContent(
					state = CellSignalReportLoadState.Idle,
					onBack = { backCount++ },
				)
			}
		}
		val backNode = composeTestRule.onNodeWithContentDescription(string(R.string.action_navigate_back))
		backNode.assertHasClickAction()
		backNode.performClick()
		assert(backCount == 1) { "Expected onBack to be invoked exactly once, was $backCount" }
	}

	private fun setContent(state: CellSignalReportLoadState) {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CellSignalReportRouteContent(state = state, onBack = {})
			}
		}
	}
}
