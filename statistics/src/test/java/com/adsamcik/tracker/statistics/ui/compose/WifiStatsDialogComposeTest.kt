package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.viewmodel.WifiStatsLoadState
import com.adsamcik.tracker.stats.api.repository.WifiObservationStatsSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiStatsDialogComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun setDialog(
		visible: Boolean = true,
		state: WifiStatsLoadState = WifiStatsLoadState.Idle,
		onDismiss: () -> Unit = {},
	) {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WifiStatsDialog(visible = visible, state = state, onDismiss = onDismiss)
			}
		}
	}

	@Test
	fun `not visible renders nothing`() {
		setDialog(visible = false, state = WifiStatsLoadState.Loading)
		composeTestRule.onNodeWithText("Wi‑Fi statistics").assertDoesNotExist()
	}

	@Test
	fun `loading state shows progress text`() {
		setDialog(state = WifiStatsLoadState.Loading)
		composeTestRule.onNodeWithText("Wi‑Fi statistics").assertIsDisplayed()
		composeTestRule.onNodeWithText("Loading Wi‑Fi statistics…").assertIsDisplayed()
	}

	@Test
	fun `idle state shows loading like loading`() {
		setDialog(state = WifiStatsLoadState.Idle)
		composeTestRule.onNodeWithText("Loading Wi‑Fi statistics…").assertIsDisplayed()
	}

	@Test
	fun `empty state shows empty message`() {
		setDialog(state = WifiStatsLoadState.Empty)
		composeTestRule.onNodeWithText("No Wi‑Fi data available").assertIsDisplayed()
	}

	@Test
	fun `error state shows error message`() {
		setDialog(state = WifiStatsLoadState.Error("DB error"))
		composeTestRule.onNodeWithText("Failed to load Wi‑Fi statistics").assertIsDisplayed()
		composeTestRule.onNodeWithText("DB error").assertIsDisplayed()
	}

	@Test
	fun `success state shows wifi stats`() {
		val summary = WifiObservationStatsSummary(
			uniqueNetworks = 150,
			totalScans = 1200,
			averageNetworksPerScan = 8.5,
		)
		setDialog(state = WifiStatsLoadState.Success(summary))
		composeTestRule.onNodeWithText("Unique networks seen").assertIsDisplayed()
		composeTestRule.onNodeWithText("Total scans").assertIsDisplayed()
		composeTestRule.onNodeWithText("Average networks per scan").assertIsDisplayed()
		composeTestRule.onNodeWithText("8.5").assertIsDisplayed()
	}

	@Test
	fun `dismiss button calls onDismiss`() {
		var dismissed = false
		setDialog(state = WifiStatsLoadState.Loading, onDismiss = { dismissed = true })
		composeTestRule.onNodeWithText("OK").performClick()
		composeTestRule.waitForIdle()
		assertTrue(dismissed)
	}
}
