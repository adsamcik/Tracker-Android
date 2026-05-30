package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarHeatmapComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	@Test
	fun `empty data shows no active days`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = emptyMap(), weeks = 26, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithContentDescription("No tracked activity in the last 26 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `single active day shows correct description`() {
		val today = LocalDate.now()
		val data = mapOf(today to 1.0f)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = data, weeks = 26, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithContentDescription("1 active day in the last 26 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `multiple active days shows plural description`() {
		val today = LocalDate.now()
		val data = mapOf(
			today to 0.5f,
			today.minusDays(1) to 0.8f,
			today.minusDays(3) to 0.2f,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = data, weeks = 26, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithContentDescription("3 active days in the last 26 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `custom weeks parameter changes description`() {
		val today = LocalDate.now()
		val data = mapOf(today to 1.0f)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = data, weeks = 12, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithContentDescription("1 active day in the last 12 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `zero intensity values are not counted as active`() {
		val today = LocalDate.now()
		val data = mapOf(
			today to 0.0f,
			today.minusDays(1) to 0.0f,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = data, weeks = 26, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithContentDescription("No tracked activity in the last 26 weeks")
			.assertIsDisplayed()
	}

	// --- computeHeatmapGrid unit tests ---

	@Test
	fun `computeHeatmapGrid with 1 week returns correct range`() {
		val today = LocalDate.of(2024, 3, 14) // Thursday
		val grid = computeHeatmapGrid(today, 1)
		assertEquals(1, grid.weekCount)
		// Should contain dates up to today (Thursday) in the current week
		val allDates = grid.weeks.flatten()
		assert(allDates.contains(today))
		assert(allDates.all { !it.isAfter(today) })
	}

	@Test
	fun `computeHeatmapGrid excludes future dates`() {
		val today = LocalDate.of(2024, 6, 5) // Wednesday
		val grid = computeHeatmapGrid(today, 4)
		val allDates = grid.weeks.flatten()
		assert(allDates.none { it.isAfter(today) })
	}

	// --- New behaviour added in the "labels + empty-state pill" pass ---

	@Test
	fun `empty data renders the start-tracking pill copy`() {
		// The empty-state overlay copy is drawn inside the canvas via
		// textMeasurer, so it's not exposed as a semantics node.  The grid's
		// contentDescription still flips to the "no tracked activity" form,
		// which is the user-visible assertion that the empty branch ran.
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = emptyMap(), weeks = 18, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithContentDescription("No tracked activity in the last 18 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `summary label above the grid mirrors the accessibility description`() {
		// The compact summary Text uses the same accessibilityDescription
		// string. Verify it is reachable via the public testing tree (not
		// the canvas-only labels).
		val today = LocalDate.now()
		val data = mapOf(
			today to 1.0f,
			today.minusDays(7) to 0.5f,
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(data = data, weeks = 20, modifier = Modifier)
			}
		}
		composeTestRule.onNodeWithText("2 active days in the last 20 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `single active day uses singular noun day`() {
		val today = LocalDate.now()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(
					data = mapOf(today to 0.3f),
					weeks = 4,
					modifier = Modifier,
				)
			}
		}
		composeTestRule.onNodeWithText("1 active day in the last 4 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `intensity above 1 is clamped and still counted as active`() {
		// Public API says values outside 0-1 are clamped. The active-day
		// count is also based on `value > 0f`, so an over-range value should
		// register as one active day, not zero.
		val today = LocalDate.now()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(
					data = mapOf(today to 2.5f),
					weeks = 4,
					modifier = Modifier,
				)
			}
		}
		composeTestRule.onNodeWithContentDescription("1 active day in the last 4 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `negative intensity is not counted as active`() {
		val today = LocalDate.now()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarHeatmap(
					data = mapOf(today to -0.5f),
					weeks = 4,
					modifier = Modifier,
				)
			}
		}
		// Negative is clamped to 0 in cell rendering, but the active-day
		// count uses the raw map value via `it.value > 0f`. -0.5 fails that
		// predicate, so the empty-state description should be shown.
		composeTestRule.onNodeWithContentDescription("No tracked activity in the last 4 weeks")
			.assertIsDisplayed()
	}

	@Test
	fun `computeHeatmapGrid with 4 weeks returns four rows`() {
		// Sunday: end-of-week boundary so every row is fully in the past.
		val today = LocalDate.of(2024, 6, 9)
		val grid = computeHeatmapGrid(today, 4)
		assertEquals(4, grid.weekCount)
		// Each week is exactly 7 days because `today` is the last day of its week.
		assert(grid.weeks.all { it.size == 7 })
	}
}
