package com.adsamcik.tracker.statistics.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.toTripDetailStepsState
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryGapReason
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.DistanceM
import com.adsamcik.tracker.stats.api.value.DurationMs
import com.adsamcik.tracker.stats.api.value.EpochMs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripDetailSourceOverviewsComposeTest {
	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun `Activity-only detail shows active time bands confidence coverage and gaps`() {
		composeRule.setContent {
			MaterialTheme {
				TripDetailActivityOverview(readyActivity())
			}
		}

		listOf(
			text(R.string.trip_detail_activity_session),
			text(R.string.trip_detail_activity_active_time),
			text(R.string.trip_detail_activity_coverage_partial),
			text(R.string.trip_detail_activity_walking),
			pluralText(R.plurals.trip_detail_activity_confidence_sampled, 3, 70, 90, 3),
			text(R.string.trip_detail_activity_known_gap),
			text(R.string.trip_detail_activity_gap_provider),
		).forEach { label ->
			composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
		}
		assertLocationProductsAbsent()
	}

	@Test
	fun `unavailable Activity-only detail never fabricates duration or bands`() {
		composeRule.setContent {
			MaterialTheme {
				TripDetailActivityOverview(unavailableActivity())
			}
		}

		composeRule.onNodeWithText(text(R.string.trip_detail_activity_unavailable))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_activity_unavailable_explanation))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_activity_timeline))
			.assertDoesNotExist()
	}

	@Test
	fun `Steps-only complete covered zero remains visible without Location claims`() {
		val history = steps(
			count = 0L,
			evidence = HistoryEvidence.ACTIVE,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		)
		renderSteps(history)

		composeRule.onNodeWithText("0").assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_complete)).assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_complete))
			.assertIsDisplayed()
		assertLocationProductsAbsent()
	}

	@Test
	fun `Steps-only positive count remains a complete retained value`() {
		val history = steps(
			count = 12_345L,
			evidence = HistoryEvidence.RECORDED,
			productState = HistoryProductState.READY,
			coverage = StepsHistoryCoverage.COMPLETE,
		)
		renderSteps(history)

		composeRule.onNodeWithText(12_345L.formatReadable()).assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_complete)).assertIsDisplayed()
	}

	@Test
	fun `sampled Activity confidence uses singular observation copy`() {
		composeRule.setContent {
			MaterialTheme {
				TripDetailActivityOverview(readyActivity(observationCount = 1))
			}
		}

		composeRule.onNodeWithText(
			pluralText(R.plurals.trip_detail_activity_confidence_sampled, 1, 70, 90, 1),
		).performScrollTo().assertIsDisplayed()
	}

	@Test
	fun `Steps-only partial history with partial coverage stays nonnumeric`() {
		val history = steps(
			count = null,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.PARTIAL,
			causes = setOf(StepsHistoryCause.PROVIDER_GAP),
		)
		renderSteps(history)

		composeRule.onNodeWithText(text(R.string.trip_detail_steps_partial_without_value))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_partial))
			.assertIsDisplayed()
		composeRule.onNodeWithText("0").assertDoesNotExist()
	}

	@Test
	fun `Steps-only partial history with no coverage does not claim partial coverage`() {
		val history = steps(
			count = null,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.FACTS_MISSING),
		)
		renderSteps(history)

		composeRule.onNodeWithText(text(R.string.trip_detail_steps_partial_without_value))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_none))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_partial))
			.assertDoesNotExist()
	}

	@Test
	fun `Steps-only partial history with unknown coverage keeps coverage unavailable`() {
		val history = steps(
			count = null,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.PARTIAL,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.EVIDENCE_STATE_UNAVAILABLE),
		)
		renderSteps(history)

		composeRule.onNodeWithText(text(R.string.trip_detail_steps_partial_without_value))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_unknown))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_partial))
			.assertDoesNotExist()
	}

	@Test
	fun `Steps-only materializing remains nonnumeric`() {
		val materializing = steps(
			count = null,
			evidence = HistoryEvidence.STARTING,
			productState = HistoryProductState.MATERIALIZING,
			coverage = StepsHistoryCoverage.NONE,
			causes = setOf(StepsHistoryCause.MATERIALIZATION_BEHIND),
		)
		renderSteps(materializing)
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_materializing))
			.assertIsDisplayed()
		composeRule.onNodeWithText("0").assertDoesNotExist()
	}

	@Test
	fun `Steps-only unavailable remains distinct from materializing`() {
		val unavailable = steps(
			count = null,
			availability = HistoryAvailability.UNAVAILABLE,
			evidence = HistoryEvidence.NONE,
			productState = HistoryProductState.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			causes = setOf(StepsHistoryCause.AVAILABILITY_UNAVAILABLE),
		)
		renderSteps(unavailable)
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_unavailable))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_materializing))
			.assertDoesNotExist()
		composeRule.onNodeWithText(text(R.string.trip_detail_steps_coverage_unknown))
			.assertIsDisplayed()
	}

	@Test
	fun `exact mixed capture without Location shows only its captured source set`() {
		composeRule.setContent {
			MaterialTheme {
				TripDetailCapturedWithoutLocationOverview(
					trip = trip(),
					capturedSources = setOf(HistorySource.ACTIVITY, HistorySource.STEPS),
				)
			}
		}

		composeRule.onNodeWithText(text(R.string.trip_detail_location_not_captured))
			.assertIsDisplayed()
		composeRule.onNodeWithText(
			"${text(R.string.trip_detail_source_activity)}, ${text(R.string.trip_detail_source_steps)}",
		).assertIsDisplayed()
		assertLocationProductsAbsent()
	}

	@Test
	fun `source-only action menu exposes disabled typed unavailability`() {
		composeRule.setContent {
			MaterialTheme {
				TripDetailUnavailableActions()
			}
		}

		composeRule.onNodeWithText(text(R.string.trip_detail_source_actions_unavailable))
			.assertIsDisplayed()
			.assertIsNotEnabled()
		composeRule.onNodeWithText(text(R.string.trip_detail_export_gpx)).assertDoesNotExist()
		composeRule.onNodeWithText(text(R.string.trip_detail_delete)).assertDoesNotExist()
	}

	@Test
	fun `legacy standard detail explains unverifiable capture authority`() {
		composeRule.setContent {
			MaterialTheme {
				TripDetailLegacyCaptureNotice()
			}
		}

		composeRule.onNodeWithText(text(R.string.trip_detail_legacy_capture_title))
			.assertIsDisplayed()
		composeRule.onNodeWithText(text(R.string.trip_detail_legacy_capture_subtitle))
			.assertIsDisplayed()
	}

	private fun renderSteps(history: StepsHistory) {
		composeRule.setContent {
			MaterialTheme {
				TripDetailStepsOverview(
					trip = trip(),
					stepsHistory = history,
					stepsState = history.toTripDetailStepsState(),
					onRetry = {},
				)
			}
		}
	}

	private fun assertLocationProductsAbsent() {
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
			composeRule.onNodeWithText(text(resource)).assertDoesNotExist()
		}
	}

	private fun readyActivity(observationCount: Int = 3) = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(5_000L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.READY,
		coverage = ActivityHistoryCoverage.PARTIAL,
		activeTime = ActivityActiveTime(
			knownActiveDurationNanos = 2_000_000_000L,
			knownInactiveDurationNanos = 1_000_000_000L,
			unknownActivityDurationNanos = 500_000_000L,
			unobservedDurationNanos = 500_000_000L,
		),
		fragments = listOf(
			ActivityHistoryFragment.Band(
				storedZoneId = "UTC",
				startTime = EpochMs(1_000L),
				endTime = EpochMs(3_000L),
				startUncertaintyMs = 100L,
				endUncertaintyMs = 100L,
				activity = ActivityHistoryType.WALKING,
				mechanism = ActivityHistoryMechanism.SAMPLED_CLASSIFICATION,
				refinedTransitionActivity = null,
				confidence = ActivityHistoryConfidence.Sampled(
					minimumPercent = 70,
					maximumPercent = 90,
					observationCount = observationCount,
				),
				wallTimeContinuity = ActivityHistoryWallTimeContinuity.SAME_ANCHOR,
				durationNanos = 2_000_000_000L,
			),
			ActivityHistoryFragment.Gap(
				storedZoneId = "UTC",
				reason = ActivityHistoryGapReason.PROVIDER_DISCONTINUITY,
				durationNanos = 500_000_000L,
			),
		),
		causes = setOf(ActivityHistoryCause.PROVIDER_GAP),
		capturesOnlyActivity = true,
	)

	private fun unavailableActivity() = ActivityHistoryEntry(
		key = ActivityHistoryEntryKey("activity"),
		startTime = EpochMs(1_000L),
		endTime = EpochMs(5_000L),
		storedZoneIds = setOf("UTC"),
		state = ActivityHistoryProductState.UNAVAILABLE,
		coverage = ActivityHistoryCoverage.NONE,
		activeTime = null,
		fragments = emptyList(),
		causes = setOf(ActivityHistoryCause.PROVIDER_UNAVAILABLE),
		capturesOnlyActivity = true,
	)

	private fun steps(
		count: Long?,
		availability: HistoryAvailability = HistoryAvailability.AVAILABLE,
		evidence: HistoryEvidence,
		productState: HistoryProductState,
		coverage: StepsHistoryCoverage,
		causes: Set<StepsHistoryCause> = emptySet(),
	) = StepsHistory(
		count = count,
		availability = availability,
		evidence = evidence,
		productState = productState,
		coverage = coverage,
		causes = causes,
	)

	private fun trip() = TripSummary(
		id = 42L,
		startTimeMs = EpochMs(1_000L),
		endTimeMs = EpochMs(5_000L),
		distance = DistanceM(9_999f),
		duration = DurationMs(4_000L),
		primaryMode = TransportMode.WALK,
		sampleCount = 99,
	)

	private fun text(id: Int, vararg args: Any): String =
		RuntimeEnvironment.getApplication().getString(id, *args)

	private fun pluralText(id: Int, quantity: Int, vararg args: Any): String =
		RuntimeEnvironment.getApplication().resources.getQuantityString(id, quantity, *args)
}
