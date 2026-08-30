package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.adsamcik.tracker.statistics.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the `TripFactsCard` (extracted to internal for these
 * tests). Covers the new Samples row and the removal of the legacy
 * Show/Hide developer-metrics affordance that previously cluttered this
 * card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripDetailRouteComposeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	private fun samplesLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.trip_detail_samples)

	private fun activityTypeLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.trip_detail_activity_type)

	private fun startTimeLabel(): String =
		RuntimeEnvironment.getApplication().getString(R.string.trip_detail_start_time)

	private fun renderFactsCard(sampleCount: Int = 48) {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				// Wrap in a vertical scroll container so the bottom rows
				// (Samples in particular) can be brought into the viewport
				// via performScrollTo. The real TripDetailRoute uses a
				// scrollable LazyColumn, so this mirrors production layout.
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
					TripFactsCard(
						activityType = "Walk",
						source = "Tracker",
						startTime = "08:30",
						endTime = "09:15",
						elevationGain = "12 m",
						elevationLoss = "3 m",
						maxAltitude = "210 m",
						sampleCount = sampleCount,
					)
				}
			}
		}
	}

	// ─── Samples row visibility ──────────────────────────────────────────

	@Test
	fun `Samples row label is visible`() {
		renderFactsCard(sampleCount = 48)
		composeTestRule.onNodeWithText(samplesLabel())
			.performScrollTo()
			.assertIsDisplayed()
	}

	@Test
	fun `Samples row renders the integer sample count as text`() {
		renderFactsCard(sampleCount = 123)
		// The card formats sampleCount as `sampleCount.toString()` — no
		// thousands separator, no unit — so the assertion matches exactly.
		composeTestRule.onNodeWithText("123")
			.performScrollTo()
			.assertIsDisplayed()
	}

	@Test
	fun `Samples row works for zero samples`() {
		// Real-world: imported / legacy sessions can have sampleCount = 0.
		// The card must still render "0" rather than hiding the row.
		renderFactsCard(sampleCount = 0)
		composeTestRule.onNodeWithText(samplesLabel())
			.performScrollTo()
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("0")
			.performScrollTo()
			.assertIsDisplayed()
	}

	// ─── Removed affordances ─────────────────────────────────────────────

	@Test
	fun `card does not render legacy Show or Hide toggle`() {
		// The dev-only Show/Hide toggle was removed; verify neither word
		// appears so a regression that re-introduces it fails loudly.
		renderFactsCard()
		composeTestRule.onNodeWithText("Show").assertDoesNotExist()
		composeTestRule.onNodeWithText("Hide").assertDoesNotExist()
	}

	@Test
	fun `card does not render Developer metrics heading`() {
		// "Developer metrics" was the title of the removed advanced section.
		// Its absence guards against accidentally re-adding the section.
		renderFactsCard()
		composeTestRule.onNodeWithText("Developer metrics").assertDoesNotExist()
		composeTestRule.onNodeWithText("DeveloperMetrics").assertDoesNotExist()
	}

	// ─── Regression coverage for surrounding rows ────────────────────────

	@Test
	fun `card still renders activity type and start time rows`() {
		// Ensures the Samples row insert didn't accidentally drop a sibling
		// FactRow above it.
		renderFactsCard()
		composeTestRule.onNodeWithText(activityTypeLabel()).assertIsDisplayed()
		composeTestRule.onNodeWithText(startTimeLabel()).assertIsDisplayed()
	}

	@Test
	fun `detail actions omit unsafe presentation-only deletion`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				Column {
					TripDetailActions(onViewOnMap = {}, onExportGpx = {})
				}
			}
		}

		composeTestRule.onNodeWithText(text(R.string.trip_detail_view_on_map)).assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_export_gpx)).assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_delete)).assertDoesNotExist()
	}

	@Test
	fun `failed Steps history exposes an explicit retry`() {
		var retried = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripDetailStepsRetry(visible = true, onRetry = { retried = true })
			}
		}

		composeTestRule.onNodeWithText(text(R.string.trip_detail_retry)).performClick()
		assertTrue(retried)
	}

	private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
