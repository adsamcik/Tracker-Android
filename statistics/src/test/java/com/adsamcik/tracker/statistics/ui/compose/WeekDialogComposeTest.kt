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
class WeekDialogComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun setDialog(
		visible: Boolean = true,
		state: StatsLoadState = StatsLoadState.Idle,
		onDismiss: () -> Unit = {},
	) {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				WeekDialog(visible = visible, state = state, onDismiss = onDismiss)
			}
		}
	}

	@Test
	fun `not visible renders nothing`() {
		setDialog(visible = false, state = StatsLoadState.Loading)
		composeTestRule.onNodeWithTag("weekDialog").assertDoesNotExist()
	}

	@Test
	fun `visible with idle state shows loading`() {
		setDialog(state = StatsLoadState.Idle)
		composeTestRule.onNodeWithText("Last 7 days").assertIsDisplayed()
		composeTestRule.onNodeWithText("Loading weekly statistics…").assertIsDisplayed()
	}

	@Test
	fun `visible with loading state shows loading`() {
		setDialog(state = StatsLoadState.Loading)
		composeTestRule.onNodeWithText("Loading weekly statistics…").assertIsDisplayed()
	}

	@Test
	fun `visible with empty success shows empty message`() {
		setDialog(state = StatsLoadState.Success(stats = emptyList()))
		composeTestRule.onNodeWithText("No weekly statistics available").assertIsDisplayed()
		composeTestRule.onNodeWithText("Track for 7 days to see your weekly summary")
			.assertIsDisplayed()
	}

	@Test
	fun `visible with success shows stat rows`() {
		val stats = listOf(
			Stat(
				nameRes = android.R.string.ok,
				iconRes = android.R.drawable.ic_menu_info_details,
				displayType = StatisticDisplayType.INFORMATION,
				data = "15 000 steps",
			),
		)
		setDialog(state = StatsLoadState.Success(stats = stats))
		composeTestRule.onNodeWithText("15 000 steps").assertIsDisplayed()
	}

	@Test
	fun `visible with error shows error`() {
		setDialog(state = StatsLoadState.Error("Timeout"))
		composeTestRule.onNodeWithText("Failed to load weekly statistics").assertIsDisplayed()
		composeTestRule.onNodeWithText("Timeout").assertIsDisplayed()
	}

	@Test
	fun `dismiss button calls onDismiss`() {
		var dismissed = false
		setDialog(state = StatsLoadState.Loading, onDismiss = { dismissed = true })
		composeTestRule.onNodeWithTag("weekDialog_dismiss").performClick()
		composeTestRule.waitForIdle()
		assertTrue(dismissed)
	}
}
