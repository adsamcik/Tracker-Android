package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.fragment.StatsScreen
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Compose UI tests for [StatsScreen] covering all refresh states and content sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsScreenComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	// ─── Loading state ──────────────────────────────────────────────────

	@Test
	fun `loading state shows loading text`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Loading,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Loading sessions…").assertIsDisplayed()
	}

	// ─── Empty state ────────────────────────────────────────────────────

	@Test
	fun `empty state shows no sessions message`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Empty,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("No tracking sessions yet").assertIsDisplayed()
	}

	// ─── Error state ────────────────────────────────────────────────────

	@Test
	fun `error state shows error message and retry button`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Error,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Something went wrong").assertIsDisplayed()
		composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
	}

	@Test
	fun `error state retry button triggers callback`() {
		var retried = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Error,
					appendState = AppendUiState.NotLoading,
					onRetry = { retried = true },
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Retry").performClick()
		assert(retried) { "onRetry should have been called" }
	}

	// ─── Content state (placeholder mode - no paging items) ──────────────

	@Test
	fun `content state shows title`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Statistics").assertIsDisplayed()
	}

	// ─── Content with heatmap data ───────────────────────────────────────

	@Test
	fun `content state with heatmap data shows heatmap section`() {
		val today = LocalDate.now()
		val heatmap = mapOf(today to 0.5f, today.minusDays(1) to 0.3f)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
					heatmapData = heatmap,
				)
			}
		}
		composeTestRule.onNodeWithText("Activity").assertIsDisplayed()
	}

	// ─── Active filter label ─────────────────────────────────────────────

	@Test
	fun `content state with active date filter shows filter label`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
					activeDateFilterLabel = "Jan 1 – Jan 31",
				)
			}
		}
		composeTestRule.onNodeWithText("Jan 1 – Jan 31").assertIsDisplayed()
	}

	// ─── Title always visible ────────────────────────────────────────────

	@Test
	fun `title is visible in loading state`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Loading,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Statistics").assertIsDisplayed()
	}

	@Test
	fun `title is visible in empty state`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Empty,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Statistics").assertIsDisplayed()
	}

	@Test
	fun `title is visible in error state`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Error,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Statistics").assertIsDisplayed()
	}
}
