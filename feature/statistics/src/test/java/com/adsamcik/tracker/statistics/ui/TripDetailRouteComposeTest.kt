package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryWindow
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.stats.api.value.StepCount
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
				Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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

	@Test
	fun `failed source history exposes retry without Location products`() {
		var retried = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripDetailSourceFailure(onRetry = { retried = true })
			}
		}

		composeTestRule.onNodeWithText(text(R.string.trip_detail_source_failed)).assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_retry)).performClick()
		assertTrue(retried)
		listOf(
			R.string.trip_detail_route_map,
			R.string.trip_detail_view_on_map,
			R.string.trip_detail_export_gpx,
			R.string.trip_detail_distance,
			R.string.trip_detail_elevation_gain,
		).forEach { resource ->
			composeTestRule.onNodeWithText(text(resource)).assertDoesNotExist()
		}
	}

	@Test
	fun `Pressure-only detail shows retained direct pressure and hides Location products`() {
		val pressure = pressureHistory(
			productState = HistoryProductState.PARTIAL,
			coverage = PressureHistoryCoverage.PARTIAL,
			windows = listOf(pressureWindow()),
			causes = setOf(PressureHistoryCause.APP_DRAIN_INCOMPLETE),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripDetailPressureOverview(trip = pressureTrip(), pressure = pressure)
			}
		}

		composeTestRule.onNodeWithText("1001.5 hPa").performScrollTo().assertIsDisplayed()
		composeTestRule.onNodeWithText("999.5–1002.0 hPa").performScrollTo().assertIsDisplayed()
		composeTestRule.onNodeWithText("+1.5 hPa").performScrollTo().assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_partial))
			.performScrollTo()
			.assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_coverage_partial))
			.performScrollTo()
			.assertIsDisplayed()

		listOf(
			R.string.trip_detail_distance,
			R.string.trip_detail_avg_speed,
			R.string.trip_detail_max_speed,
			R.string.trip_detail_pace,
			R.string.trip_detail_elevation_gain,
			R.string.trip_detail_elevation_loss,
			R.string.trip_detail_max_altitude,
			R.string.trip_detail_route_map,
			R.string.trip_detail_view_on_map,
			R.string.trip_detail_export_gpx,
			R.string.trip_detail_samples,
		).forEach { resource ->
			composeTestRule.onNodeWithText(text(resource)).assertDoesNotExist()
		}
	}

	@Test
	fun `materializing Pressure without retained facts stays typed and nonnumeric`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				TripDetailPressureCard(
					pressureHistory(
						productState = HistoryProductState.MATERIALIZING,
						coverage = PressureHistoryCoverage.NONE,
						windows = emptyList(),
						causes = setOf(PressureHistoryCause.PRODUCT_LANE_BEHIND),
					),
				)
			}
		}

		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_materializing))
			.assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_coverage_none))
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("0.0 hPa").assertDoesNotExist()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_latest)).assertDoesNotExist()
	}

	@Test
	fun `unavailable and failed Pressure remain distinct nonnumeric states`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				Column {
					TripDetailPressureCard(
						pressureHistory(
							availability = HistoryAvailability.UNAVAILABLE,
							productState = HistoryProductState.DEGRADED,
							coverage = PressureHistoryCoverage.UNKNOWN,
							windows = emptyList(),
							causes = setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
						),
					)
					TripDetailPressureCard(
						pressureHistory(
							productState = HistoryProductState.FAILED,
							coverage = PressureHistoryCoverage.NONE,
							windows = emptyList(),
							causes = setOf(PressureHistoryCause.TERMINAL_PROJECTION_FAILURE),
						),
					)
				}
			}
		}

		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_unavailable))
			.performScrollTo()
			.assertIsDisplayed()
		composeTestRule.onNodeWithText(text(R.string.trip_detail_pressure_failed))
			.performScrollTo()
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("0.0 hPa").assertDoesNotExist()
	}

	private fun pressureHistory(
		availability: HistoryAvailability = HistoryAvailability.AVAILABLE,
		productState: HistoryProductState,
		coverage: PressureHistoryCoverage,
		windows: List<PressureHistoryWindow>,
		causes: Set<PressureHistoryCause>,
	) = PressureHistory(
		availability = availability,
		evidence = if (windows.isEmpty()) HistoryEvidence.NONE else HistoryEvidence.RECORDED,
		productState = productState,
		coverage = coverage,
		windows = windows,
		causes = causes,
	)

	private fun pressureWindow() = PressureHistoryWindow(
		intervalStartTime = EpochMs(1_000L),
		intervalEndTime = EpochMs(2_000L),
		sampleCount = 5,
		expectedSampleCount = 5,
		meanHectopascals = 1000.5,
		sumSquaredDeviations = 1.0,
		minimumHectopascals = 999.5f,
		maximumHectopascals = 1002.0f,
		firstHectopascals = 1000.0f,
		latestHectopascals = 1001.5f,
		slopeHectopascalsPerSecond = 0.1,
		rSquared = 0.8,
		sensorAccuracy = PressureSensorAccuracy.HIGH,
		effectiveSamplePeriodMicros = 200_000,
		effectiveMaximumReportLatencyMicros = 0,
		targetWindowDurationNanos = 1_000_000_000L,
		maximumInterSampleGapNanos = 200_000_000L,
		closure = PressureWindowClosure.TARGET_ELAPSED,
		qualification = PressureWindowQualification.COMPLETE,
		sourceQualityFlags = 0L,
		sourceQualityConfidence = 1f,
		zoneId = "Europe/Prague",
	)

	private fun pressureTrip() = TripSummary(
		id = 42L,
		startTimeMs = EpochMs(1_000L),
		endTimeMs = EpochMs(5_000L),
		distance = DistanceM(0f),
		steps = StepCount(0),
		duration = DurationMs(4_000L),
		primaryMode = TransportMode.UNKNOWN,
		sampleCount = 0,
	)

	private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)
}
