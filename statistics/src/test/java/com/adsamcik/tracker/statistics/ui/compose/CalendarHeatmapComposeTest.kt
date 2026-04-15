package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
}
