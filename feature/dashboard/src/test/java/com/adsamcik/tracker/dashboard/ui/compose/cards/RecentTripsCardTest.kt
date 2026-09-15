package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryEntry
import com.adsamcik.tracker.dashboard.data.DashboardRecentHistoryState
import com.adsamcik.tracker.shared.model.SegmentSource
import com.adsamcik.tracker.shared.model.Trip
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.HistoryAvailability
import com.adsamcik.tracker.stats.api.repository.HistoryEvidence
import com.adsamcik.tracker.stats.api.repository.HistoryProductState
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCause
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryWindow
import com.adsamcik.tracker.stats.api.repository.PressureOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.PressureSensorAccuracy
import com.adsamcik.tracker.stats.api.repository.PressureWindowClosure
import com.adsamcik.tracker.stats.api.repository.PressureWindowQualification
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity
import com.adsamcik.tracker.stats.api.repository.StepsOnlyHistoryListState
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryEntryKey
import com.adsamcik.tracker.stats.api.value.EpochMs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentTripsCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyHistoryShowsNeutralEmptyMessage() {
		setContent(DashboardRecentHistoryState.Content(emptyList()))

		composeRule.onNodeWithText("Recent Tracking").assertIsDisplayed()
		composeRule.onNodeWithText(
			"No recent tracking sessions. Start tracking to see them here.",
		).assertIsDisplayed()
	}

	@Test
	fun loadingIsRenderedDistinctly() {
		setContent(DashboardRecentHistoryState.Loading)
		composeRule.onNodeWithText("Loading recent tracking…").assertIsDisplayed()
	}

	@Test
	fun unavailableIsRenderedDistinctly() {
		setContent(DashboardRecentHistoryState.Unavailable)
		composeRule.onNodeWithText("Recent tracking is unavailable").assertIsDisplayed()
	}

	@Test
	fun physicalRowPreservesMetricsAndClickIdentity() {
		var clickedId: Long? = null
		setContent(
			DashboardRecentHistoryState.Content(
				listOf(DashboardRecentHistoryEntry.Physical(trip(42L))),
			),
			onTripClick = { clickedId = it },
		)

		composeRule.onNodeWithText("km", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("30 m", substring = true).assertIsDisplayed()
		composeRule.onNodeWithContentDescription("View details").performClick()
		clickedId shouldBe 42L
	}

	@Test
	fun allStepsStatesAreNonnumericNonclickableAndOpaqueKeysDoNotCollide() {
		val now = System.currentTimeMillis()
		setContent(
			DashboardRecentHistoryState.Content(
				StepsOnlyHistoryListState.entries.mapIndexed { index, state ->
					DashboardRecentHistoryEntry.StepsOnly(
						stepsEntry(
							key = "opaque-$index",
							state = state,
							startTimeMs = now - (index + 2) * 3_600_000L,
						),
					)
				},
			),
			onTripClick = { error("Steps-only rows must not expose a physical click identity") },
		)

		composeRule.onAllNodesWithText("Steps session").assertCountEquals(3)
		composeRule.onNodeWithText("Steps history available")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithText("Preparing Steps history…")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithText("Partial Steps history")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription("View details").assertCountEquals(0)
	}

	@Test
	fun ActivityOnlyRowShowsCapturedEvidenceWithoutLocationMetricsOrAction() {
		setContent(
			DashboardRecentHistoryState.Content(
				listOf(DashboardRecentHistoryEntry.ActivityOnly(activityEntry())),
			),
			onTripClick = { error("Activity-only rows must not expose a physical click identity") },
		)

		composeRule.onNodeWithTag("dashboard_recent_activity_row")
			.assertIsDisplayed()
			.assertHasNoClickAction()
		composeRule.onNodeWithText("Activity session").assertIsDisplayed()
		composeRule.onNodeWithText("Walking · active 1 m").assertIsDisplayed()
		composeRule.onNodeWithText("Partial coverage").assertIsDisplayed()
		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("Distance", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription("View details").assertCountEquals(0)
	}

	@Test
	fun distinctOpaqueKeysWithIdenticalStringRepresentationRenderBothRows() {
		val first = stepsEntry("first", StepsOnlyHistoryListState.AVAILABLE, 1_000L)
		val second = stepsEntry("second", StepsOnlyHistoryListState.PARTIAL, 2_000L)
		first.key.toString() shouldBe second.key.toString()

		setContent(
			DashboardRecentHistoryState.Content(
				listOf(
					DashboardRecentHistoryEntry.StepsOnly(first),
					DashboardRecentHistoryEntry.StepsOnly(second),
				),
			),
		)

		composeRule.onAllNodesWithText("Steps session").assertCountEquals(2)
	}

	@Test
	fun namespacedOpaqueKeysRenderStepsAndPressureWithTheSamePrivateValue() {
		val now = System.currentTimeMillis()
		setContent(
			DashboardRecentHistoryState.Content(
				listOf(
					DashboardRecentHistoryEntry.StepsOnly(
						stepsEntry("shared", StepsOnlyHistoryListState.AVAILABLE, now - 2_000L),
					),
					DashboardRecentHistoryEntry.PressureOnly(
						pressureEntry(withWindow = false, key = "shared"),
					),
				),
			),
		)

		composeRule.onNodeWithText("Steps session").assertIsDisplayed()
		composeRule.onNodeWithText("Pressure session").assertIsDisplayed()
	}

	@Test
	fun pressureOnlyRowShowsDirectPressureWithoutPhysicalActionsOrDerivedMetrics() {
		setContent(
			DashboardRecentHistoryState.Content(
				listOf(DashboardRecentHistoryEntry.PressureOnly(pressureEntry(withWindow = true))),
			),
			onTripClick = { error("Pressure-only rows must not expose a physical click identity") },
		)

		composeRule.onNodeWithText("Pressure session").assertIsDisplayed().assertHasNoClickAction()
		composeRule.onNodeWithText("Pressure history available").assertIsDisplayed()
		composeRule.onNodeWithText("1001.5 hPa · range 999.5–1002.0 hPa").assertIsDisplayed()
		composeRule.onNodeWithText("Change 1.5 hPa · Complete coverage").assertIsDisplayed()
		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("elevation", substring = true, ignoreCase = true)
			.assertCountEquals(0)
		composeRule.onAllNodesWithText("ascent", substring = true, ignoreCase = true)
			.assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription("View details").assertCountEquals(0)
	}

	@Test
	fun unavailablePressureOnlyRowDoesNotFabricateNumericPressure() {
		setContent(
			DashboardRecentHistoryState.Content(
				listOf(DashboardRecentHistoryEntry.PressureOnly(pressureEntry(withWindow = false))),
			),
		)

		composeRule.onNodeWithText("Pressure history unavailable").assertIsDisplayed()
		composeRule.onAllNodesWithText("hPa", substring = true).assertCountEquals(0)
	}

	@Test
	fun getTripIconSupportsGmsNativeAndSkiIds() {
		getTripIcon(7) shouldBe Icons.AutoMirrored.Filled.DirectionsWalk
		getTripIcon(-2) shouldBe Icons.AutoMirrored.Filled.DirectionsWalk
		getTripIcon(1) shouldBe Icons.AutoMirrored.Filled.DirectionsBike
		getTripIcon(-4) shouldBe Icons.AutoMirrored.Filled.DirectionsBike
		getTripIcon(-22) shouldBe Icons.Filled.DownhillSkiing
	}

	private fun setContent(
		recentHistory: DashboardRecentHistoryState,
		onTripClick: ((Long) -> Unit)? = null,
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				RecentTripsCard(
					recentHistory = recentHistory,
					onTripClick = onTripClick,
				)
			}
		}
	}

	private fun trip(id: Long): Trip {
		val start = System.currentTimeMillis() - 3_600_000L
		return Trip(
			id = id,
			startTimeMs = start,
			endTimeMs = start + 1_800_000L,
			distanceM = 2_500f,
			steps = null,
			primaryActivity = null,
			activityConfidence = null,
			sampleCount = 10,
			source = SegmentSource.USER_CREATED,
			createdAt = start,
		)
	}

	private fun stepsEntry(
		key: String,
		state: StepsOnlyHistoryListState,
		startTimeMs: Long,
	) = StepsOnlyHistoryEntry(
		key = TrackingHistoryEntryKey(key),
		startTime = EpochMs(startTimeMs),
		endTime = EpochMs(startTimeMs + 1_800_000L),
		state = state,
	)

	private fun pressureEntry(
		withWindow: Boolean,
		key: String = if (withWindow) "pressure-ready" else "pressure-unavailable",
	): PressureOnlyHistoryEntry {
		val now = System.currentTimeMillis()
		val windows = if (withWindow) listOf(pressureWindow(now)) else emptyList()
		return PressureOnlyHistoryEntry(
			key = TrackingHistoryEntryKey(key),
			startTime = EpochMs(now - 1_800_000L),
			endTime = EpochMs(now),
			pressure = PressureHistory(
				availability = if (withWindow) {
					HistoryAvailability.AVAILABLE
				} else {
					HistoryAvailability.UNAVAILABLE
				},
				evidence = if (withWindow) HistoryEvidence.RECORDED else HistoryEvidence.NONE,
				productState = if (withWindow) HistoryProductState.READY else HistoryProductState.DEGRADED,
				coverage = if (withWindow) {
					PressureHistoryCoverage.COMPLETE
				} else {
					PressureHistoryCoverage.UNKNOWN
				},
				windows = windows,
				causes = if (withWindow) emptySet() else setOf(PressureHistoryCause.PROVIDER_UNAVAILABLE),
			),
		)
	}

	private fun pressureWindow(now: Long) = PressureHistoryWindow(
		intervalStartTime = EpochMs(now - 1_000L),
		intervalEndTime = EpochMs(now),
		sampleCount = 5,
		expectedSampleCount = 5,
		meanHectopascals = 1000.5,
		sumSquaredDeviations = 1.0,
		minimumHectopascals = 999.5f,
		maximumHectopascals = 1002.0f,
		firstHectopascals = 1000.0f,
		latestHectopascals = 1001.5f,
		slopeHectopascalsPerSecond = 0.01,
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

	private fun activityEntry(): ActivityHistoryEntry {
		val now = System.currentTimeMillis()
		return ActivityHistoryEntry(
			key = ActivityHistoryEntryKey("activity"),
			startTime = EpochMs(now - 60_000L),
			endTime = EpochMs(now),
			storedZoneIds = setOf("UTC"),
			state = ActivityHistoryProductState.PARTIAL,
			coverage = ActivityHistoryCoverage.PARTIAL,
			activeTime = ActivityActiveTime(60_000_000_000L, 0L, 0L, 0L),
			fragments = listOf(
				ActivityHistoryFragment.Band(
					storedZoneId = "UTC",
					startTime = EpochMs(now - 60_000L),
					endTime = EpochMs(now),
					startUncertaintyMs = 0L,
					endUncertaintyMs = 0L,
					activity = ActivityHistoryType.WALKING,
					mechanism = ActivityHistoryMechanism.TRANSITION,
					refinedTransitionActivity = null,
					confidence = ActivityHistoryConfidence.TransitionSignal,
					wallTimeContinuity = ActivityHistoryWallTimeContinuity.SAME_ANCHOR,
					durationNanos = 60_000_000_000L,
				),
			),
			causes = setOf(ActivityHistoryCause.PROVIDER_GAP),
			capturesOnlyActivity = true,
		)
	}
}
