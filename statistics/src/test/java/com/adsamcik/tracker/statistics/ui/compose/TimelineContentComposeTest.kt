package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.statistics.ui.TimelineContent
import com.adsamcik.tracker.statistics.viewmodel.TimelineEntry
import com.adsamcik.tracker.statistics.viewmodel.TimelineState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [TimelineContent].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineContentComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	// ─── Loading state ──────────────────────────────────────────────────

	@Test
	fun `loading state shows progress indicator`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Loading,
					onTripClick = {},
				)
			}
		}
		// Loading state shows CircularProgressIndicator; no text entries
		composeTestRule.onNodeWithText("No activity yet").assertDoesNotExist()
	}

	// ─── Empty state ────────────────────────────────────────────────────

	@Test
	fun `empty state shows empty message`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Empty,
					onTripClick = {},
				)
			}
		}
		composeTestRule.onNodeWithText("No activity yet").assertIsDisplayed()
		composeTestRule.onNodeWithText("Start tracking to see your movement history here")
			.assertIsDisplayed()
	}

	// ─── Content state with trip entry ───────────────────────────────────

	@Test
	fun `content state shows trip entry`() {
		val entries = listOf(
			TimelineEntry.TripEntry(
				tripId = 1L,
				timestampMs = 1_700_000_000_000L,
				title = "Morning Walk",
				subtitle = "2.5 km · 30 min",
				timeLabel = "08:30",
				modeIcon = Icons.AutoMirrored.Filled.DirectionsWalk,
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Content(entries),
					onTripClick = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Morning Walk").assertIsDisplayed()
		composeTestRule.onNodeWithText("2.5 km · 30 min").assertIsDisplayed()
		composeTestRule.onNodeWithText("08:30").assertIsDisplayed()
	}

	// ─── Content state with discovery entry ──────────────────────────────

	@Test
	fun `content state shows discovery entry`() {
		val entries = listOf(
			TimelineEntry.DiscoveryEntry(
				id = "disc_1",
				timestampMs = 1_700_000_000_000L,
				title = "New Area",
				subtitle = "3 new cells discovered",
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Content(entries),
					onTripClick = {},
				)
			}
		}
		composeTestRule.onNodeWithText("New Area").assertIsDisplayed()
		composeTestRule.onNodeWithText("3 new cells discovered").assertIsDisplayed()
	}

	// ─── Content state with day summary entry ────────────────────────────

	@Test
	fun `content state shows day summary entry`() {
		val entries = listOf(
			TimelineEntry.DaySummaryEntry(
				epochDay = 19000L,
				timestampMs = 1_700_000_000_000L,
				dateLabel = "Monday, Nov 14",
				distanceLabel = "5.2 km",
				stepsLabel = "7,200 steps",
				tripCount = 3,
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Content(entries),
					onTripClick = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Monday, Nov 14").assertIsDisplayed()
		composeTestRule.onNodeWithText("5.2 km").assertIsDisplayed()
		composeTestRule.onNodeWithText("7,200 steps").assertIsDisplayed()
		composeTestRule.onNodeWithText("3 trips").assertIsDisplayed()
	}

	// ─── Trip click callback ─────────────────────────────────────────────

	@Test
	fun `tapping trip entry triggers onTripClick`() {
		var clickedId: Long? = null
		val entries = listOf(
			TimelineEntry.TripEntry(
				tripId = 42L,
				timestampMs = 1_700_000_000_000L,
				title = "Clickable Trip",
				subtitle = "1.0 km",
				timeLabel = "10:00",
				modeIcon = Icons.Filled.DirectionsCar,
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Content(entries),
					onTripClick = { clickedId = it },
				)
			}
		}
		composeTestRule.onNodeWithText("Clickable Trip").performClick()
		assert(clickedId == 42L) { "Expected trip id 42 but got $clickedId" }
	}

	// ─── Mixed entries render together ───────────────────────────────────

	@Test
	fun `multiple entry types render together`() {
		val entries = listOf(
			TimelineEntry.DaySummaryEntry(
				epochDay = 19000L,
				timestampMs = 1_700_000_002_000L,
				dateLabel = "Summary Day",
				distanceLabel = "10 km",
				stepsLabel = "12k steps",
				tripCount = 5,
			),
			TimelineEntry.TripEntry(
				tripId = 1L,
				timestampMs = 1_700_000_001_000L,
				title = "Trip A",
				subtitle = "sub A",
				timeLabel = "09:00",
				modeIcon = Icons.AutoMirrored.Filled.DirectionsWalk,
			),
			TimelineEntry.DiscoveryEntry(
				id = "disc_2",
				timestampMs = 1_700_000_000_000L,
				title = "Discovery B",
				subtitle = "sub B",
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Content(entries),
					onTripClick = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Summary Day").assertIsDisplayed()
		composeTestRule.onNodeWithText("Trip A").assertIsDisplayed()
		composeTestRule.onNodeWithText("Discovery B").assertIsDisplayed()
	}

	// ─── Implausible distance flag ───────────────────────────────────────

	@Test
	fun `trip entry with implausible distance renders`() {
		val entries = listOf(
			TimelineEntry.TripEntry(
				tripId = 99L,
				timestampMs = 1_700_000_000_000L,
				title = "Anomaly Trip",
				subtitle = "999 km (anomaly)",
				timeLabel = "12:00",
				modeIcon = Icons.AutoMirrored.Filled.DirectionsWalk,
				isDistancePlausible = false,
			),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TimelineContent(
					state = TimelineState.Content(entries),
					onTripClick = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Anomaly Trip").assertIsDisplayed()
	}
}
