package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.stats.api.repository.StepsNumericDay
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummary
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.fragment.StatsScreen
import com.adsamcik.tracker.statistics.fragment.collectWeeklyStepsSummaryWhenVisible
import com.adsamcik.tracker.statistics.fragment.shouldCollectWeeklyStepsSummary
import com.adsamcik.tracker.statistics.viewmodel.DayBar
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
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
	fun `qualified Steps collection is limited to sparse content visibility`() {
		RefreshUiState.Loading.shouldCollectWeeklyStepsSummary(visibleSessionCount = 1) shouldBe false
		RefreshUiState.Empty.shouldCollectWeeklyStepsSummary(visibleSessionCount = 1) shouldBe false
		RefreshUiState.Error.shouldCollectWeeklyStepsSummary(visibleSessionCount = 1) shouldBe false
		RefreshUiState.Content.shouldCollectWeeklyStepsSummary(visibleSessionCount = 0) shouldBe false
		RefreshUiState.Content.shouldCollectWeeklyStepsSummary(visibleSessionCount = 1) shouldBe true
		RefreshUiState.Content.shouldCollectWeeklyStepsSummary(visibleSessionCount = 2) shouldBe true
		RefreshUiState.Content.shouldCollectWeeklyStepsSummary(visibleSessionCount = 3) shouldBe false
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
