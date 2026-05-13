package com.adsamcik.tracker.statistics.ui.compose

import com.adsamcik.tracker.shared.base.database.data.SegmentSource
import com.adsamcik.tracker.shared.base.database.data.Trip
import com.adsamcik.tracker.statistics.ui.TripCard
import com.adsamcik.tracker.statistics.ui.formatDuration
import com.adsamcik.tracker.statistics.ui.formatTripTimeRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [TripCard] and helper functions in TripsContent.kt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripsContentComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun sampleTrip(
		id: Long = 1L,
		startTimeMs: Long = 1_700_000_000_000L,
		endTimeMs: Long = 1_700_001_800_000L, // 30 min later
		distanceM: Float = 2500f,
		steps: Int? = 3000,
		primaryActivity: Int? = 7, // Walk
		hasDistanceAnomaly: Boolean = false,
	) = Trip(
		id = id,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		distanceM = distanceM,
		steps = steps,
		primaryActivity = primaryActivity,
		activityConfidence = 90,
		sampleCount = 100,
		source = SegmentSource.USER_CREATED,
		createdAt = startTimeMs,
		hasDistanceAnomaly = hasDistanceAnomaly,
	)

	// ─── TripCard rendering ─────────────────────────────────────────────

	@Test
	fun `trip card displays activity label and distance`() {
		val trip = sampleTrip()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("Walk").assertIsDisplayed()
		composeTestRule.onNodeWithText("2.5 km").assertIsDisplayed()
	}

	@Test
	fun `trip card displays duration`() {
		val trip = sampleTrip()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("30m").assertIsDisplayed()
	}

	@Test
	fun `trip card click callback triggers`() {
		var clicked = false
		val trip = sampleTrip()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = { clicked = true })
			}
		}
		composeTestRule.onNodeWithText("Walk").performClick()
		assert(clicked) { "onClick should have been called" }
	}

	@Test
	fun `trip card map action callback triggers`() {
		var viewedOnMap = false
		val trip = sampleTrip()
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(
					trip = trip,
					onClick = {},
					onViewOnMap = { viewedOnMap = true },
				)
			}
		}
		composeTestRule.onNodeWithContentDescription("View trip on map").performClick()
		viewedOnMap shouldBe true
	}

	// ─── Anomaly trip ────────────────────────────────────────────────────

	@Test
	fun `trip card with anomaly shows distance`() {
		val trip = sampleTrip(hasDistanceAnomaly = true, distanceM = 99999f)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("100.0 km").assertIsDisplayed()
	}

	// ─── Different activities ────────────────────────────────────────────

	@Test
	fun `trip card with run activity shows Run label`() {
		val trip = sampleTrip(primaryActivity = 8)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("Run").assertIsDisplayed()
	}

	@Test
	fun `trip card with drive activity shows Drive label`() {
		val trip = sampleTrip(primaryActivity = 0)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("Drive").assertIsDisplayed()
	}

	@Test
	fun `trip card with unknown activity shows Trip label`() {
		val trip = sampleTrip(primaryActivity = null)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("Trip").assertIsDisplayed()
	}

	// ─── Utility function tests ──────────────────────────────────────────

	@Test
	fun `formatDuration formats hours and minutes`() {
		formatDuration(3_661_000L) shouldBe "1h 1m"
	}

	@Test
	fun `formatDuration formats minutes only`() {
		formatDuration(300_000L) shouldBe "5m"
	}

	@Test
	fun `formatDuration handles zero`() {
		formatDuration(0L) shouldBe "0m"
	}

	@Test
	fun `formatTripTimeRange returns formatted range`() {
		val result = formatTripTimeRange(1_700_000_000_000L, 1_700_001_800_000L)
		// Should contain a dash separator
		assert(result.contains(" - ")) { "Expected time range format but got: $result" }
	}

	// ─── Short distance (meters) ─────────────────────────────────────────

	@Test
	fun `trip card with short distance shows meters`() {
		val trip = sampleTrip(distanceM = 500f)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("500 m").assertIsDisplayed()
	}

	// ─── Long duration ───────────────────────────────────────────────────

	@Test
	fun `trip card with multi-hour duration shows hours and minutes`() {
		val trip = sampleTrip(
			endTimeMs = 1_700_000_000_000L + 7_200_000L, // 2h
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripCard(trip = trip, onClick = {})
			}
		}
		composeTestRule.onNodeWithText("2h 0m").assertIsDisplayed()
	}
}
