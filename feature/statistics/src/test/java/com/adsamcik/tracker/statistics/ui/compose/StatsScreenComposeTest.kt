package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.fragment.StatsScreen
import com.adsamcik.tracker.statistics.fragment.collectWeeklyStepsSummaryWhenVisible
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
		composeTestRule.onNodeWithText("N/A").assertIsDisplayed()
	}

	@Test
	fun `sparse summary keeps partial Steps nonnumeric`() {
		val todayEpochDay = LocalDate.now().toEpochDay()
		val trip = sparseSummaryTrip()
		val weeklyBars = List(3) { index ->
			DayBar(
				dayLabel = "Day $index",
				distanceM = 0f,
				epochDay = todayEpochDay - index,
				sessionCount = 1,
			)
		}

		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreenWithSingleSession(
					trip = trip,
					weeklyBars = weeklyBars,
					weeklyStepsSummary = StepsNumericSummary.Unverifiable(
						StepsNumericUnverifiableReason.PARTIAL_CAPTURE,
					),
				)
			}
		}

		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithText("N/A").assertIsDisplayed()
	}

	@Test
	fun `sparse summary displays source-qualified covered zero`() {
		val todayEpochDay = LocalDate.now().toEpochDay()

		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreenWithSingleSession(
					trip = sparseSummaryTrip(),
					weeklyBars = listOf(
						DayBar(
							dayLabel = "Today",
							distanceM = 0f,
							epochDay = todayEpochDay,
							sessionCount = 1,
						),
					),
					weeklyStepsSummary = StepsNumericSummary.Ready(
						listOf(StepsNumericDay(epochDay = todayEpochDay, steps = 0L)),
					),
				)
			}
		}

		composeTestRule.waitForIdle()
		composeTestRule.onAllNodesWithText("0").fetchSemanticsNodes().size shouldBe 2
		composeTestRule.onNodeWithContentDescription("Today, 0")
			.performScrollTo()
			.assertIsDisplayed()
	}

	@Test
	fun `sparse summary keeps materializing Steps nonnumeric`() {
		val todayEpochDay = LocalDate.now().toEpochDay()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreenWithSingleSession(
					trip = sparseSummaryTrip(),
					weeklyBars = listOf(
						DayBar(
							dayLabel = "Today",
							distanceM = 0f,
							epochDay = todayEpochDay,
						),
					),
					weeklyStepsSummary = StepsNumericSummary.Materializing,
				)
			}
		}

		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithText("N/A").assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Today, —")
			.performScrollTo()
			.assertIsDisplayed()
	}

	@Test
	fun `saved all-day Steps render without sessions and keep Summary and History reachable`() {
		val today = LocalDate.now().toEpochDay()
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 42L))),
		)
		var summaryOpened = false
		var historyOpened = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(
					summaries = summaries,
					weeklyBars = listOf(DayBar("Today", distanceM = 0f, epochDay = today)),
					onShowSummary = { summaryOpened = true },
					onNavigateToHistory = { historyOpened = true },
				)
			}
		}
		composeTestRule.waitForIdle()
		summaries.subscriptionCount.value shouldBe 1
		composeTestRule.onNode(hasText("42") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNode(hasText("1") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Today, 42").assertIsDisplayed()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
		composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
		composeTestRule.onNodeWithText("No tracking sessions yet").assertDoesNotExist()
		composeTestRule.onNodeWithText("Summary").performClick()
		composeTestRule.onNodeWithText("History").performScrollTo().performClick()
		summaryOpened shouldBe true
		historyOpened shouldBe true
	}

	@Test
	fun `sessionless covered zero remains a measured Steps zero`() {
		val today = LocalDate.now().toEpochDay()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(
					summaries = remember {
						MutableStateFlow<StepsNumericSummary>(
							StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 0L))),
						)
					},
					weeklyBars = listOf(DayBar("Today", distanceM = 0f, epochDay = today)),
				)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNode(hasText("0") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNode(hasText("0") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Today, 0").assertIsDisplayed()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
		composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
	}

	@Test
	fun `sessionless retained Steps updates keep waiting partial and unavailable states nonnumeric`() {
		val today = LocalDate.now().toEpochDay()
		val summaries = MutableStateFlow<StepsNumericSummary>(StepsNumericSummary.Materializing)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(
					summaries = summaries,
					weeklyBars = listOf(DayBar("Today", distanceM = 0f, epochDay = today)),
				)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithText("Updating…").assertIsDisplayed()
		composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
		composeTestRule.onNode(hasText("N/A") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()

		composeTestRule.runOnIdle {
			summaries.value = StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 53L)))
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNode(hasText("53") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()

		listOf(
			StepsNumericUnverifiableReason.PARTIAL_CAPTURE to "Incomplete",
			StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE to "Unavailable",
			StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE to "Unavailable",
			StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE to "Unavailable",
		).forEach { (reason, text) ->
			composeTestRule.runOnIdle { summaries.value = StepsNumericSummary.Unverifiable(reason) }
			composeTestRule.waitForIdle()
			composeTestRule.onNodeWithText(text).assertIsDisplayed()
			composeTestRule.onNode(hasText("53") and hasAncestor(hasTestTag("stats_summary_steps")))
				.assertDoesNotExist()
			composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
			composeTestRule.onNode(hasText("N/A") and hasAncestor(hasTestTag("stats_summary_active_days")))
				.assertIsDisplayed()
			composeTestRule.onNodeWithContentDescription("Today, —").assertIsDisplayed()
		}
	}

	@Test
	fun `sessionless not-captured data keeps existing Start action and History access`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED),
		)
		var started = false
		var historyOpened = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(
					summaries = summaries,
					onStartTracking = { started = true },
					onNavigateToHistory = { historyOpened = true },
				)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithText("Start Tracking").performScrollTo().performClick()
		composeTestRule.onNodeWithText("History").performScrollTo().performClick()
		composeTestRule.onNodeWithTag("stats_sparse_summary").assertDoesNotExist()
		started shouldBe true
		historyOpened shouldBe true
	}

	@Test
	fun `known weekly activity without qualified Steps keeps data actions instead of Start prompt`() {
		val today = LocalDate.now().toEpochDay()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(
					summaries = remember {
						MutableStateFlow<StepsNumericSummary>(
							StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED),
						)
					},
					weeklyBars = listOf(
						DayBar("Today", distanceM = 0f, durationMs = 60_000L, epochDay = today),
					),
				)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithText("Not captured").assertIsDisplayed()
		composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
		composeTestRule.onNodeWithText("Summary").assertHasClickAction()
		composeTestRule.onNodeWithText("History").assertHasClickAction()
	}

	@Test
	fun `empty saved Steps screen never inserts test placeholder sessions`() {
		val today = LocalDate.now().toEpochDay()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				StatsScreen(
					refreshState = RefreshUiState.Empty,
					appendState = AppendUiState.NotLoading,
					onRetry = {},
					onShowSummary = {},
					onShowWeek = {},
					onOpenWifi = {},
					weeklyBars = listOf(DayBar("Today", distanceM = 0f, epochDay = today)),
					weeklyStepsSummary = StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 19L))),
				)
			}
		}
		composeTestRule.onNode(hasText("19") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("session").assertDoesNotExist()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
	}

	@Test
	@Config(qualifiers = "cs-rCZ")
	fun `Czech sessionless empty copy does not claim all recorded data is absent`() {
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
		composeTestRule.onNodeWithText("Zatím žádné relace").assertIsDisplayed()
		composeTestRule.onNodeWithText("Nebyly nalezeny žádné záznamy. Zatím.").assertDoesNotExist()
	}

	@Test
	fun `session paging loading does not hide saved all-day Steps`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(listOf(StepsNumericDay(LocalDate.now().toEpochDay(), 32L))),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(summaries = summaries, refreshState = RefreshUiState.Loading)
			}
		}
		composeTestRule.waitForIdle()
		summaries.subscriptionCount.value shouldBe 1
		composeTestRule.onNodeWithText("Loading sessions…").assertIsDisplayed()
		composeTestRule.onNode(hasText("32") and hasAncestor(hasTestTag("stats_summary_steps")))
			.performScrollTo().assertIsDisplayed()
		composeTestRule.onNode(hasText("1") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
		composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
		composeTestRule.onNodeWithText("Summary").assertHasClickAction()
	}

	@Test
	fun `session paging failure preserves retained Steps and existing Retry action`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(listOf(StepsNumericDay(LocalDate.now().toEpochDay(), 44L))),
		)
		var retried = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(
					summaries = summaries,
					refreshState = RefreshUiState.Error,
					onRetry = { retried = true },
				)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithText("Something went wrong").assertIsDisplayed()
		composeTestRule.onNodeWithText("Retry").performClick()
		composeTestRule.onNode(hasText("44") and hasAncestor(hasTestTag("stats_summary_steps")))
			.performScrollTo().assertIsDisplayed()
		composeTestRule.onNode(hasText("1") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
		composeTestRule.onNodeWithText("Start Tracking").assertDoesNotExist()
		composeTestRule.onNodeWithText("History").assertHasClickAction()
		retried shouldBe true
	}

	@Test
	fun `qualified Steps day rows and active days do not wait for session summary bars`() {
		val today = LocalDate.of(2026, 10, 3).toEpochDay()
		val bars = mutableStateOf(emptyList<DayBar>())
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(
				listOf(StepsNumericDay(today - 1L, 6L), StepsNumericDay(today, 11L)),
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				SessionlessStepsStatistics(summaries = summaries, weeklyBars = bars.value)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNode(hasText("17") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNode(hasText("2") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Fri, 6").assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Sat, 11").assertIsDisplayed()
		composeTestRule.runOnIdle {
			bars.value = listOf(DayBar("Today", distanceM = 0f, epochDay = today))
		}
		composeTestRule.waitForIdle()
		composeTestRule.onNodeWithContentDescription("Fri, 6").assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Today, 11").assertIsDisplayed()
		composeTestRule.onNode(hasText("2") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
	}

	@Test
	fun `qualified Steps collector subscribes and cancels across visibility transitions`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(StepsNumericSummary.Materializing)
		val visible = mutableStateOf(false)
		val lifecycleOwner = TestLifecycleOwner()
		var rendered: StepsNumericSummary? = null

		composeTestRule.setContent {
			CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
				rendered = collectWeeklyStepsSummaryWhenVisible(
					summaries = summaries,
					visible = visible.value,
				)
			}
		}
		composeTestRule.runOnIdle {
			lifecycleOwner.moveTo(Lifecycle.Event.ON_CREATE)
			lifecycleOwner.moveTo(Lifecycle.Event.ON_START)
		}
		composeTestRule.waitForIdle()
		summaries.subscriptionCount.value shouldBe 0
		rendered shouldBe StepsNumericSummary.Materializing

		composeTestRule.runOnIdle { visible.value = true }
		composeTestRule.waitForIdle()
		summaries.subscriptionCount.value shouldBe 1
		val ready = StepsNumericSummary.Ready(
			listOf(StepsNumericDay(epochDay = LocalDate.now().toEpochDay(), steps = 12L)),
		)
		composeTestRule.runOnIdle { summaries.value = ready }
		composeTestRule.waitForIdle()
		rendered shouldBe ready

		composeTestRule.runOnIdle { visible.value = false }
		composeTestRule.waitForIdle()
		summaries.subscriptionCount.value shouldBe 0
		composeTestRule.runOnIdle {
			rendered shouldBe StepsNumericSummary.Materializing
			lifecycleOwner.moveTo(Lifecycle.Event.ON_STOP)
			lifecycleOwner.moveTo(Lifecycle.Event.ON_DESTROY)
		}
	}

	// ─── Active filter label ─────────────────────────────────────────────

	@Test
	fun `three sessions preserve saved all-day Steps when Steps is off`() {
		val today = LocalDate.now().toEpochDay()
		val largeCount = Int.MAX_VALUE.toLong() + 8L
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(List(7) { index ->
				StepsNumericDay(today - 6L + index, if (index == 6) largeCount else 0L)
			}),
		)
		var openedTrip: Long? = null
		var historyOpened = false
		var loadedSessionCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				DenseStepsStatistics(
					summaries = summaries,
					stepsSelection = remember { MutableStateFlow(false) },
					onTripClick = { openedTrip = it },
					onNavigateToHistory = { historyOpened = true },
					onSessionCount = { loadedSessionCount = it },
					weeklyBars = List(7) { index ->
						DayBar("Day $index", distanceM = 0f, epochDay = today - 6L + index)
					},
				)
			}
		}
		composeTestRule.waitForIdle()
		summaries.subscriptionCount.value shouldBe 1
		loadedSessionCount shouldBe 3
		composeTestRule.onNodeWithTag("stats_summary_steps").assertIsDisplayed()
		composeTestRule.onNode(hasScrollToIndexAction())
			.performScrollToNode(hasTestTag("stats_session_row"))
		composeTestRule.onAllNodesWithTag("stats_session_row")[0].performClick()
		openedTrip shouldBe 1L
		scrollToWeeklyStepsSummary()
		composeTestRule.onNode(hasText(largeCount.formatReadable()) and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNode(hasText("1") and hasAncestor(hasTestTag("stats_summary_active_days")))
			.assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Day 0, 0").assertIsDisplayed()
		composeTestRule.onNodeWithContentDescription("Day 6, ${largeCount.formatReadable()}").assertIsDisplayed()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
		composeTestRule.onNodeWithText("Only 3 sessions are visible", substring = true).assertDoesNotExist()
		scrollToStatisticsHeader()
		composeTestRule.onNodeWithText("History").performClick()
		historyOpened shouldBe true
	}

	@Test
	fun `saved Steps appears before a long session history without scrolling`() {
		val today = LocalDate.now().toEpochDay()
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 123L))),
		)
		val trips = List(50) { index -> sparseSummaryTrip().copy(id = index.toLong() + 1L) }
		var loadedSessionCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				DenseStepsStatistics(
					summaries = summaries,
					stepsSelection = remember { MutableStateFlow(false) },
					sessionPages = remember { MutableStateFlow(PagingData.from(trips)) },
					onSessionCount = { loadedSessionCount = it },
				)
			}
		}
		composeTestRule.waitForIdle()
		loadedSessionCount shouldBe 50
		composeTestRule.onNodeWithText("History").assertIsDisplayed()
		composeTestRule.onNodeWithTag("stats_summary_steps").assertIsDisplayed()
		composeTestRule.onNode(hasText("123") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		summaries.subscriptionCount.value shouldBe 1
	}

	@Test
	fun `selected Steps keeps waiting and incomplete states alongside three sessions`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED),
		)
		val selected = MutableStateFlow(true)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				DenseStepsStatistics(summaries = summaries, stepsSelection = selected)
			}
		}
		val states = listOf(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED) to "Not captured",
			StepsNumericSummary.Materializing to "Updating…",
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE) to "Incomplete",
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE) to "Unavailable",
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE) to "Unavailable",
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE) to "Unavailable",
		)
		states.forEach { (state, label) ->
			composeTestRule.runOnIdle { summaries.value = state }
			composeTestRule.waitForIdle()
			scrollToWeeklyStepsSummary()
			composeTestRule.onNode(hasText(label) and hasAncestor(hasTestTag("stats_summary_steps")))
				.assertIsDisplayed()
			composeTestRule.onNode(hasText("0") and hasAncestor(hasTestTag("stats_summary_steps")))
				.assertDoesNotExist()
			composeTestRule.onNode(hasText("N/A") and hasAncestor(hasTestTag("stats_summary_active_days")))
				.assertIsDisplayed()
			composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
			scrollToStatisticsHeader()
			composeTestRule.onNodeWithText("History").assertHasClickAction()
		}
		summaries.subscriptionCount.value shouldBe 1
	}

	@Test
	fun `saved incomplete Steps stays visible after switching Steps off with three sessions`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.PARTIAL_CAPTURE),
		)
		val selected = MutableStateFlow(true)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				DenseStepsStatistics(summaries = summaries, stepsSelection = selected)
			}
		}
		composeTestRule.waitForIdle()
		composeTestRule.runOnIdle { selected.value = false }
		composeTestRule.waitForIdle()
		scrollToWeeklyStepsSummary()
		composeTestRule.onNode(hasText("Incomplete") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNode(hasText("0") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertDoesNotExist()
		scrollToStatisticsHeader()
		composeTestRule.onNodeWithText("History").assertHasClickAction()
		summaries.subscriptionCount.value shouldBe 1
	}

	@Test
	fun `off Steps without saved coverage does not add a weekly card to three sessions`() {
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED),
		)
		val selected = MutableStateFlow(false)
		var historyOpened = false
		var loadedSessionCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				DenseStepsStatistics(
					summaries = summaries,
					stepsSelection = selected,
					onNavigateToHistory = { historyOpened = true },
					onSessionCount = { loadedSessionCount = it },
				)
			}
		}
		listOf(
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.NOT_CAPTURED),
			StepsNumericSummary.Materializing,
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.STORAGE_UNAVAILABLE),
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.CALENDAR_AUTHORITY_UNAVAILABLE),
			StepsNumericSummary.Unverifiable(StepsNumericUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE),
		).forEach { state ->
			composeTestRule.runOnIdle { summaries.value = state }
			composeTestRule.waitForIdle()
			composeTestRule.onNodeWithTag("stats_sparse_summary").assertDoesNotExist()
			loadedSessionCount shouldBe 3
			composeTestRule.onAllNodesWithTag("stats_session_row")[0].assertIsDisplayed()
		}
		composeTestRule.onNodeWithText("History").performClick()
		historyOpened shouldBe true
		summaries.subscriptionCount.value shouldBe 1
	}

	@Test
	fun `loading a third session cannot unsubscribe saved Steps or block later count updates`() {
		val today = LocalDate.now().toEpochDay()
		val summaries = MutableStateFlow<StepsNumericSummary>(
			StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 61L))),
		)
		val sessions = MutableStateFlow(PagingData.from(denseSummaryTrips().take(2)))
		var loadedSessionCount = 0
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				DenseStepsStatistics(
					summaries = summaries,
					stepsSelection = remember { MutableStateFlow(false) },
					sessionPages = sessions,
					onSessionCount = { loadedSessionCount = it },
				)
			}
		}
		composeTestRule.waitForIdle()
		loadedSessionCount shouldBe 2
		scrollToWeeklyStepsSummary()
		composeTestRule.onNode(hasText("61") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.runOnIdle { sessions.value = PagingData.from(denseSummaryTrips()) }
		composeTestRule.waitForIdle()
		loadedSessionCount shouldBe 3
		summaries.subscriptionCount.value shouldBe 1
		composeTestRule.runOnIdle {
			summaries.value = StepsNumericSummary.Ready(listOf(StepsNumericDay(today, 77L)))
		}
		composeTestRule.waitForIdle()
		scrollToWeeklyStepsSummary()
		composeTestRule.onNode(hasText("77") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertIsDisplayed()
		composeTestRule.onNode(hasText("61") and hasAncestor(hasTestTag("stats_summary_steps")))
			.assertDoesNotExist()
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
		scrollToStatisticsHeader()
		composeTestRule.onNodeWithText("History").assertHasClickAction()
		summaries.subscriptionCount.value shouldBe 1
	}

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
	private fun SessionlessStepsStatistics(
		summaries: StateFlow<StepsNumericSummary>,
		weeklyBars: List<DayBar> = emptyList(),
		refreshState: RefreshUiState = RefreshUiState.Empty,
		onRetry: () -> Unit = {},
		onShowSummary: () -> Unit = {},
		onNavigateToHistory: () -> Unit = {},
		onStartTracking: () -> Unit = {},
	) {
		val sessions = remember { flowOf(PagingData.empty<Trip>()) }.collectAsLazyPagingItems()
		val summary = collectWeeklyStepsSummaryWhenVisible(
			summaries = summaries,
			visible = true,
		)
		StatsScreen(
			refreshState = refreshState,
			appendState = AppendUiState.NotLoading,
			onRetry = onRetry,
			onShowSummary = onShowSummary,
			onShowWeek = {},
			onOpenWifi = {},
			onNavigateToHistory = onNavigateToHistory,
			onStartTracking = onStartTracking,
			sessions = sessions,
			weeklyBars = weeklyBars,
			weeklyStepsSummary = summary,
		)
	}

	@Composable
	private fun DenseStepsStatistics(
		summaries: StateFlow<StepsNumericSummary>,
		stepsSelection: StateFlow<Boolean>,
		weeklyBars: List<DayBar> = emptyList(),
		sessionPages: StateFlow<PagingData<Trip>> = remember {
			MutableStateFlow(PagingData.from(denseSummaryTrips()))
		},
		onTripClick: (Long) -> Unit = {},
		onNavigateToHistory: () -> Unit = {},
		onSessionCount: (Int) -> Unit = {},
	) {
		val sessions = sessionPages.collectAsLazyPagingItems()
		onSessionCount(sessions.itemCount)
		val selected = stepsSelection.collectAsStateWithLifecycle().value
		val summary = collectWeeklyStepsSummaryWhenVisible(summaries, visible = true)
		StatsScreen(
			refreshState = RefreshUiState.Content,
			appendState = AppendUiState.NotLoading,
			onRetry = {},
			onShowSummary = {},
			onShowWeek = {},
			onOpenWifi = {},
			onTripClick = onTripClick,
			onNavigateToHistory = onNavigateToHistory,
			sessions = sessions,
			weeklyBars = weeklyBars,
			weeklyStepsSummary = summary,
			stepsSelected = selected,
		)
	}

	private fun denseSummaryTrips() = List(3) { index ->
		sparseSummaryTrip().copy(id = index.toLong() + 1L)
	}

	private fun scrollToWeeklyStepsSummary() {
		composeTestRule.onNode(hasScrollToIndexAction())
			.performScrollToNode(hasTestTag("stats_summary_steps"))
	}

	private fun scrollToStatisticsHeader() {
		composeTestRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
	}

	@Composable
	private fun StatsScreenWithSingleSession(
		trip: Trip,
		weeklyBars: List<DayBar>,
		weeklyStepsSummary: StepsNumericSummary = StepsNumericSummary.Unverifiable(
			StepsNumericUnverifiableReason.NOT_CAPTURED,
		),
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
			weeklyStepsSummary = weeklyStepsSummary,
		)
	}

	private fun sparseSummaryTrip() = Trip(
		id = 1L,
		startTimeMs = System.currentTimeMillis() - 57_000L,
		endTimeMs = System.currentTimeMillis(),
		distanceM = 0f,
		steps = 0,
		primaryActivity = null,
		activityConfidence = null,
		sampleCount = 0,
		source = SegmentSource.USER_CREATED,
		createdAt = System.currentTimeMillis(),
	)

	private class TestLifecycleOwner : LifecycleOwner {
		private val registry = LifecycleRegistry(this)
		override val lifecycle: Lifecycle = registry

		fun moveTo(event: Lifecycle.Event) {
			registry.handleLifecycleEvent(event)
		}
	}

}
