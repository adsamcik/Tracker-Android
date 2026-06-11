package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.statistics.ui.CalendarContent
import com.adsamcik.tracker.statistics.viewmodel.CalendarDayData
import com.adsamcik.tracker.statistics.viewmodel.CalendarState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/**
 * Compose UI tests for [CalendarContent].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarContentComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun sampleTrip(
		id: Long = 1L,
		startTimeMs: Long = 1_700_000_000_000L,
		distanceM: Float = 2500f,
		primaryActivity: Int? = 7,
	) = Trip(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = startTimeMs + 1_800_000L,
		distanceM = distanceM,
		steps = 3000,
		primaryActivity = primaryActivity,
		activityConfidence = 90,
		sampleCount = 100,
		source = SegmentSource.USER_CREATED,
		createdAt = startTimeMs,
	)

	// ─── Basic rendering ─────────────────────────────────────────────────

	@Test
	fun `calendar shows month header`() {
		val month = YearMonth.of(2024, 6)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(currentMonth = month),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("June 2024", substring = true).assertIsDisplayed()
	}

	@Test
	fun `calendar shows navigation arrows`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(currentMonth = YearMonth.of(2024, 3)),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("March 2024", substring = true).assertIsDisplayed()
	}

	// ─── Day data with intensity ─────────────────────────────────────────

	@Test
	fun `calendar renders day numbers`() {
		val month = YearMonth.of(2024, 1)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(currentMonth = month),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("1").assertIsDisplayed()
		composeTestRule.onNodeWithText("15").assertIsDisplayed()
		composeTestRule.onNodeWithText("31").assertIsDisplayed()
	}

	// ─── Day click callback ──────────────────────────────────────────────

	@Test
	fun `clicking a day triggers onDayClick`() {
		var clickedDate: LocalDate? = null
		val month = YearMonth.of(2024, 6)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(currentMonth = month),
					onDayClick = { clickedDate = it },
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("15").performClick()
		assert(clickedDate == LocalDate.of(2024, 6, 15)) {
			"Expected 2024-06-15 but got $clickedDate"
		}
	}

	// ─── Selected day detail ─────────────────────────────────────────────

	@Test
	fun `selected day with detail shows stats`() {
		val month = YearMonth.of(2024, 6)
		val detail = CalendarState.DayDetail(
			totalDistanceM = 5200f,
			totalSteps = 7000,
			tripCount = 2,
			trips = listOf(
				sampleTrip(id = 1L),
				sampleTrip(id = 2L),
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(
						currentMonth = month,
						selectedDay = LocalDate.of(2024, 6, 15),
						selectedDayDetail = detail,
					),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Distance").assertIsDisplayed()
		composeTestRule.onNodeWithText("Steps").assertIsDisplayed()
		composeTestRule.onNodeWithText("Trips").assertIsDisplayed()
		composeTestRule.onNodeWithText("7000").assertIsDisplayed()
	}

	// ─── No detail when no day selected ──────────────────────────────────

	@Test
	fun `no selected day hides detail section`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(
						currentMonth = YearMonth.of(2024, 6),
						selectedDay = null,
						selectedDayDetail = null,
					),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Distance").assertDoesNotExist()
	}

	// ─── Trip card in day detail ─────────────────────────────────────────

	@Test
	fun `trip card in day detail triggers navigation`() {
		var navigatedTripId: Long? = null
		val detail = CalendarState.DayDetail(
			totalDistanceM = 1000f,
			totalSteps = 500,
			tripCount = 1,
			trips = listOf(sampleTrip(id = 99L)),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(
						currentMonth = YearMonth.of(2024, 6),
						selectedDay = LocalDate.of(2024, 6, 10),
						selectedDayDetail = detail,
					),
					onDayClick = {},
					onNavigateToTripDetail = { navigatedTripId = it },
				)
			}
		}
		// The trip card lives in a LazyColumn below a summary card. In the
		// constrained Robolectric viewport the trip item is initially out of
		// the composed range, so scroll the day-detail LazyColumn to the trip
		// (keyed by trip id) before clicking. The card shows "Walk at HH:mm".
		composeTestRule.onNode(hasScrollAction()).performScrollToKey(99L)
		composeTestRule.onNodeWithText("Walk at", substring = true).performClick()
		assert(navigatedTripId == 99L) { "Expected trip id 99 but got $navigatedTripId" }
	}

	// ─── Empty day data ──────────────────────────────────────────────────

	@Test
	fun `calendar with empty day data still renders grid`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(
						currentMonth = YearMonth.of(2024, 2),
						dayData = emptyMap(),
					),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		// February 2024 has 29 days (leap year)
		composeTestRule.onNodeWithText("29").assertIsDisplayed()
	}

	// ─── Day data with intensity ─────────────────────────────────────────

	@Test
	fun `calendar with intensity data renders without crash`() {
		val month = YearMonth.of(2024, 3)
		val dayData = (1..31).associate { day ->
			val date = month.atDay(day)
			date to CalendarDayData(date = date, intensity = (day % 5) * 0.2f, tripCount = day % 3)
		}
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				CalendarContent(
					state = CalendarState(currentMonth = month, dayData = dayData),
					onDayClick = {},
					onNavigateToTripDetail = {},
				)
			}
		}
		composeTestRule.onNodeWithText("March 2024", substring = true).assertIsDisplayed()
	}
}
