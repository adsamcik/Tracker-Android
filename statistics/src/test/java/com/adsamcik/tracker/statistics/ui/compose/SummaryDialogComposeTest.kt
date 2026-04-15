package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import com.adsamcik.tracker.statistics.viewmodel.StatsLoadState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryDialogComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun setDialog(
		visible: Boolean = true,
		state: StatsLoadState = StatsLoadState.Idle,
		onDismiss: () -> Unit = {},
	) {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SummaryDialog(visible = visible, state = state, onDismiss = onDismiss)
			}
		}
	}

	@Test
	fun `not visible renders nothing`() {
		setDialog(visible = false, state = StatsLoadState.Loading)
		composeTestRule.onNodeWithTag("summaryDialog").assertDoesNotExist()
	}

	@Test
	fun `visible with idle state shows loading indicator`() {
		setDialog(state = StatsLoadState.Idle)
		composeTestRule.onNodeWithText("Summary").assertIsDisplayed()
		composeTestRule.onNodeWithText("Loading summary…").assertIsDisplayed()
	}

	@Test
	fun `visible with loading state shows loading indicator`() {
		setDialog(state = StatsLoadState.Loading)
		composeTestRule.onNodeWithText("Loading summary…").assertIsDisplayed()
	}

	@Test
	fun `visible with empty success shows empty state`() {
		setDialog(state = StatsLoadState.Success(stats = emptyList()))
		composeTestRule.onNodeWithText("No summary statistics available").assertIsDisplayed()
		composeTestRule.onNodeWithText("Start tracking to see your activity summary")
			.assertIsDisplayed()
	}

	@Test
	fun `visible with success shows stat rows`() {
		val stats = listOf(
			Stat(
				nameRes = android.R.string.ok,
				iconRes = android.R.drawable.ic_menu_info_details,
				displayType = StatisticDisplayType.INFORMATION,
				data = "42 km",
			),
		)
		setDialog(state = StatsLoadState.Success(stats = stats))
		composeTestRule.onNodeWithText("42 km").assertIsDisplayed()
	}

	@Test
	fun `visible with error shows error message`() {
		setDialog(state = StatsLoadState.Error("Database unavailable"))
		composeTestRule.onNodeWithText("Failed to load summary").assertIsDisplayed()
		composeTestRule.onNodeWithText("Database unavailable").assertIsDisplayed()
	}

	@Test
	fun `dismiss button calls onDismiss`() {
		var dismissed = false
		setDialog(state = StatsLoadState.Idle, onDismiss = { dismissed = true })
		composeTestRule.onNodeWithTag("summaryDialog_dismiss").performClick()
		composeTestRule.waitForIdle()
		assertTrue(dismissed)
	}
}
