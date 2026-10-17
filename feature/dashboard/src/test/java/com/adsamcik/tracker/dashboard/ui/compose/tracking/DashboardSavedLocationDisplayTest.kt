package com.adsamcik.tracker.dashboard.ui.compose.tracking

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.model.AltitudeDatum
import com.adsamcik.tracker.shared.model.AltitudeSource
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.preferences.extension.formatSpeed
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.SavedLocationFix
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummary
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummaryRead
import com.adsamcik.tracker.stats.api.repository.SavedLocationSummarySelection
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryReadSnapshot
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.adsamcik.tracker.tracker.R as TrackerR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardSavedLocationDisplayTest {
	@get:Rule
	val composeRule = createComposeRule()
	private val context: Context = ApplicationProvider.getApplicationContext()

	@Test
	fun savedLocationShowsRouteAccuracyAndMeasuredAverageWithoutLegacyCollection() {
		show(saved(partial = true))

		composeRule.onAllNodesWithText(context.getString(R.string.dashboard_tracking_recording_route))
			.assertCountEquals(0)
		composeRule.onAllNodesWithText(context.getString(R.string.dashboard_tracking_awaiting_gps))
			.assertCountEquals(0)
		val partial = context.getString(TrackerR.string.tracker_measurement_partial)
		composeRule.onAllNodesWithText("${distance(123.0)} · $partial").assertCountEquals(2)
		val average = context.resources.formatSpeed(context, 2.5, 1)
		composeRule.onNodeWithText("$average · $partial").assertIsDisplayed()
		composeRule.onNodeWithText("±${distance(5.0)}").assertIsDisplayed()
	}

	@Test
	fun previousSegmentCannotSupplyCurrentFixOrDistance() {
		show(saved(segmentId = 8L, includePreview = true))

		composeRule.onAllNodesWithText(context.getString(R.string.dashboard_tracking_awaiting_gps))
			.assertCountEquals(0)
		composeRule.onAllNodesWithText(context.getString(TrackerR.string.tracker_steps_today_waiting))
			.assertCountEquals(3)
		composeRule.onAllNodesWithText(distance(123.0)).assertCountEquals(0)
		composeRule.onAllNodesWithText("±${distance(5.0)}").assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription(context.getString(R.string.dashboard_cd_session_path, 2))
			.assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription(context.getString(R.string.dashboard_cd_session_path, 3))
			.assertCountEquals(0)
	}

	@Test
	fun savedPreviewRendersWithoutLegacyPathPoints() {
		show(saved(includePreview = true))

		composeRule.onNodeWithContentDescription(context.getString(R.string.dashboard_cd_session_path, 2))
			.assertIsDisplayed()
		composeRule.onAllNodesWithText(context.getString(R.string.dashboard_tracking_awaiting_gps))
			.assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription(context.getString(R.string.dashboard_cd_session_path, 3))
			.assertCountEquals(0)
	}

	@Test
	fun missingSavedMeasurementDoesNotShowLegacyDistanceOrMeasuredZero() {
		show(SavedLocationSummaryRead.NotFound)

		composeRule.onAllNodesWithText(distance(0.0)).assertCountEquals(0)
		composeRule.onAllNodesWithText(distance(9_000_000.0)).assertCountEquals(0)
		composeRule.onAllNodesWithText(context.getString(TrackerR.string.tracker_steps_today_waiting))
			.assertCountEquals(3)
		composeRule.onAllNodesWithText(context.getString(R.string.dashboard_tracking_awaiting_gps))
			.assertCountEquals(0)
		composeRule.onAllNodesWithContentDescription(context.getString(R.string.dashboard_cd_session_path, 3))
			.assertCountEquals(0)
	}

	@Test
	fun measuredStationaryPairRetainsZeroDistanceAndAverage() {
		val read = saved(includePreview = true).let {
			it.copy(summary = it.summary.copy(distanceMeters = 0.0, movingAverageSpeedMetersPerSecond = 0.0,
				routePreview = it.summary.routePreview.map { section ->
					section.map { point -> point.copy(longitudeE7 = 10_000_000) }
				}))
		}
		show(read)

		composeRule.onAllNodesWithText(distance(0.0)).assertCountEquals(2)
		composeRule.onNodeWithText(context.resources.formatSpeed(context, 0.0, 1)).assertIsDisplayed()
		composeRule.onNodeWithContentDescription(context.getString(R.string.dashboard_cd_session_path, 2))
			.assertIsDisplayed()
	}

	private fun show(read: SavedLocationSummaryRead) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingContent(
					state = DashboardUiState(
						isTracking = true,
						sessionData = TrackerSessionSnapshot(id = 7L, start = 1_000L,
							end = 61_000L, distanceInM = 9_000_000f),
						savedLocation = read,
						pathPoints = List(3) { index -> Location(0L, 20.0 + index, 20.0 + index,
							null, null, null, null, null) },
					),
					onMapClick = {},
				)
			}
		}
	}

	private fun distance(value: Double): String = context.resources.formatDistance(
		value,
		if (value >= 1000.0) { 1 } else { 0 },
		TrackerSettingsQuick.lengthSystem(context),
	)

	private fun saved(segmentId: Long = 7L, partial: Boolean = false, includePreview: Boolean = false):
		SavedLocationSummaryRead.Snapshot {
		val fix = SavedLocationFix(1L, 2_000L, 0L, 0, 10_000_000, 0f, null, 5f,
			-12f, AltitudeDatum.WGS84_ELLIPSOID, AltitudeSource.UNKNOWN_LEGACY, null)
		return SavedLocationSummaryRead.Snapshot(
			SavedLocationSummarySelection.Session(segmentId),
			TrackingHistoryReadSnapshot(0L, 1L),
			SavedLocationSummary(
				pointCount = 2L,
				latestFix = fix,
				distanceMeters = 123.0,
				partial = partial,
				movingAverageSpeedMetersPerSecond = 2.5,
				routePreview = if (includePreview) {
					listOf(listOf(fix.copy(sampleId = 2L, measuredTimeMs = 1_000L, longitudeE7 = 0), fix))
				} else {
					emptyList()
				},
			),
		)
	}
}
