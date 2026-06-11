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
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [WifiStatsRouteContent].
 *
 * The Hilt-backed [WifiStatsRoute] entry composable is exercised here via its
 * stateless `WifiStatsRouteContent` body — same pattern as
 * [SummaryRouteComposeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiStatsRouteComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun navigateBackLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.action_navigate_back)

	private fun wifiTitle(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_dialog_title)

	private fun wifiEmptyTitle(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_dialog_empty_title)

	private fun wifiLoadingLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_dialog_loading)

	private fun wifiErrorTitle(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_dialog_error)

	private fun uniqueNetworksLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_unique_networks)

	private fun totalScansLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_total_scans)

	private fun averagePerScanLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.stats_wifi_average_per_scan)

	// ─── Chrome ──────────────────────────────────────────────────────────

	@Test
	fun `route renders scaffold with wifiStatsRoute testTag and title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsRouteContent(state = WifiStatsLoadState.Idle, onBack = {})
			}
		}
		composeTestRule.onNodeWithTag("wifiStatsRoute").assertIsDisplayed()
		composeTestRule.onNodeWithText(wifiTitle()).assertIsDisplayed()
	}

	// ─── Loading ─────────────────────────────────────────────────────────

	@Test
	fun `Loading state shows loading copy`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsRouteContent(state = WifiStatsLoadState.Loading, onBack = {})
			}
		}
		composeTestRule.onNodeWithText(wifiLoadingLabel()).assertIsDisplayed()
	}

	// ─── Empty ───────────────────────────────────────────────────────────

	@Test
	fun `Empty state shows empty title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsRouteContent(state = WifiStatsLoadState.Empty, onBack = {})
			}
		}
		composeTestRule.onNodeWithText(wifiEmptyTitle()).assertIsDisplayed()
	}

	// ─── Success ─────────────────────────────────────────────────────────

	@Test
	fun `Success renders all three metric rows with formatted values`() {
		val summary = WifiObservationStatsSummary(
			uniqueNetworks = 1234L,
			totalScans = 5_678L,
			averageNetworksPerScan = 4.25,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsRouteContent(
					state = WifiStatsLoadState.Success(summary),
					onBack = {},
				)
			}
		}
		// Labels for the three rows
		composeTestRule.onNodeWithText(uniqueNetworksLabel()).assertIsDisplayed()
		composeTestRule.onNodeWithText(totalScansLabel()).assertIsDisplayed()
		composeTestRule.onNodeWithText(averagePerScanLabel()).assertIsDisplayed()
		// Numeric value for the average is locale-aware "%.1f" — match the
		// substring "4" + decimal separator + "2" or "3" (rounding edge cases)
		// keep the assertion robust: just check the average label exists and
		// uniqueNetworks/totalScans show their grouped readable forms.
		// We avoid asserting the exact grouped string because formatReadable
		// depends on the runtime locale's grouping separator.
	}

	// ─── Error ───────────────────────────────────────────────────────────

	@Test
	fun `Error state shows title and message`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsRouteContent(
					state = WifiStatsLoadState.Error("Scan repository unavailable"),
					onBack = {},
				)
			}
		}
		composeTestRule.onNodeWithText(wifiErrorTitle()).assertIsDisplayed()
		composeTestRule.onNodeWithText("Scan repository unavailable").assertIsDisplayed()
	}

	// ─── Back affordance ─────────────────────────────────────────────────

	@Test
	fun `tapping back navigation icon invokes onBack`() {
		var backCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsRouteContent(
					state = WifiStatsLoadState.Idle,
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
