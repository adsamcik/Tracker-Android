package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.fragment.StatsScreen
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import kotlinx.coroutines.flow.flowOf
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
		composeTestRule.onNodeWithText("Retry").assertHasClickAction()
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
	fun `content state shows header action chips`() {
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
		composeTestRule.onNodeWithText("Summary").assertIsDisplayed()
	}

	// ─── Header action chips are clickable and trigger callbacks ─────────

	@Test
	fun `summary chip is clickable and triggers callback`() {
		var summaryClicked = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = { summaryClicked = true },
					onShowWeek = {},
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Summary").assertIsDisplayed()
		composeTestRule.onNodeWithText("Summary").assertHasClickAction()
		composeTestRule.onNodeWithText("Summary").performClick()
		assert(summaryClicked) { "onShowSummary should have been called" }
	}

	@Test
	fun `dates chip is clickable and triggers callback`() {
		var weekClicked = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = { weekClicked = true },
					onOpenWifi = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Dates").assertIsDisplayed()
		composeTestRule.onNodeWithText("Dates").assertHasClickAction()
		composeTestRule.onNodeWithText("Dates").performClick()
		assert(weekClicked) { "onShowWeek should have been called" }
	}

	@Test
	fun `wifi chip is clickable and triggers callback`() {
		var wifiClicked = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = { wifiClicked = true },
				)
			}
		}
		val wifiLabel = "Wi\u2011Fi stats"
		composeTestRule.onNodeWithText(wifiLabel).assertIsDisplayed()
		composeTestRule.onNodeWithText(wifiLabel).assertHasClickAction()
		composeTestRule.onNodeWithText(wifiLabel).performClick()
		assert(wifiClicked) { "onOpenWifi should have been called" }
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
		composeTestRule.onNodeWithTag("stats_calendar_heatmap").assertIsDisplayed()
	}

	@Test
	fun `sparse summary daily activity shows distance when today session has no steps`() {
		val today = LocalDate.now()
		val todayEpochDay = today.toEpochDay()
		val trip = Trip(
			id = 1L,
			startTimeMs = System.currentTimeMillis() - 57_000L,
			endTimeMs = System.currentTimeMillis(),
			distanceM = 304f,
			steps = 0,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 4,
			source = SegmentSource.USER_CREATED,
			createdAt = System.currentTimeMillis(),
		)
		val weeklyBars = listOf(
			DayBar(
				dayLabel = "Today",
				distanceM = 304f,
				steps = 0,
				epochDay = todayEpochDay,
				sessionCount = 1,
				durationMs = 57_000L,
			),
		)

		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreenWithSingleSession(
					trip = trip,
					weeklyBars = weeklyBars,
				)
			}
		}

		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithTag("stats_sparse_summary").assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Today, 304 m")
			.performScrollTo()
			.assertIsDisplayed()
	}

	// ─── Active filter label ─────────────────────────────────────────────

	@Test
	fun `content state with active date filter shows filter label with correct text`() {
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

	@Test
	fun `content state with different date filter shows correct label`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Content,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
					activeDateFilterLabel = "Mar 15 – Apr 15",
				)
			}
		}
		composeTestRule.onNodeWithText("Mar 15 – Apr 15").assertIsDisplayed()
	}

	@Composable
	private fun StatsScreenWithSingleSession(
		trip: Trip,
		weeklyBars: List<DayBar>,
	) {
		val sessions = flowOf(PagingData.from(listOf(trip))).collectAsLazyPagingItems()
		StatsScreen(
			refreshState = RefreshUiState.Content,
			appendState = AppendUiState.NotLoading,
			onRetry = {},
			onShowSummary = {},
			onShowWeek = {},
			onOpenWifi = {},
			sessions = sessions,
			weeklyBars = weeklyBars,
		)
	}

}
