package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Additional tests for SessionOverviewCard and SessionPathPreview
 * covering gaps in the existing TrackerDashboardSessionCardsTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardSessionCardsAdditionalTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun createSession(
		id: Long = 1L,
		start: Long = Time.nowMillis - 3600_000L,
		end: Long = Time.nowMillis,
		distanceInM: Float = 5000f,
		distanceOnFootInM: Float = 4000f,
		distanceInVehicleInM: Float = 1000f,
		steps: Int = 7500,
		collections: Int = 120,
	) = TrackerSession(
		id = id,
		start = start,
		end = end,
		isUserInitiated = true,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceOnFootInM,
		distanceInVehicleInM = distanceInVehicleInM,
		steps = steps,
	)

	private fun createPoints(count: Int, baseTime: Long = Time.nowMillis - 3600_000L): List<Location> {
		return (0 until count).map { i ->
			Location(
				time = baseTime + i * 60_000L,
				latitude = 50.0 + i * 0.001,
				longitude = 14.0 + i * 0.001,
				altitude = 200.0 + i,
				horizontalAccuracy = 5f,
				verticalAccuracy = 3f,
				speed = 1.5f,
				speedAccuracy = 0.5f,
			)
		}
	}

	// region SessionOverviewCard - null onDetailClick

	@Test
	fun sessionOverviewCard_nullDetailClick_rendersWithoutCrash() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					onDetailClick = null,
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session overview",
		).assertIsDisplayed()
	}

	// endregion

	// region SessionOverviewCard - tracking with null pathPoints

	@Test
	fun sessionOverviewCard_trackingNullPath_showsTrackingBadge() {
		val session = createSession(end = 0)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = true,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
	}

	// endregion

	// region SessionOverviewCard - large distance formatting

	@Test
	fun sessionOverviewCard_largeDistance_rendersKilometers() {
		val session = createSession(distanceInM = 15234f)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session overview",
		).assertIsDisplayed()
	}

	// endregion

	// region SessionOverviewCard - metrics displayed

	@Test
	fun sessionOverviewCard_showsDurationMetric() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithText("Session duration", substring = true, useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun sessionOverviewCard_showsDistanceMetric() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithText("Distance", substring = true, useUnmergedTree = true)
			.assertIsDisplayed()
	}

	@Test
	fun sessionOverviewCard_showsStepsMetric() {
		val session = createSession(steps = 3500)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithText("Steps", substring = true, useUnmergedTree = true)
			.assertIsDisplayed()
	}

	// endregion

	// region SessionPathPreview - many points

	@Test
	fun sessionPathPreview_manyPoints_rendersWithoutCrash() {
		val points = createPoints(50)
		composeRule.setContent {
			AppTheme {
				SessionPathPreview(
					points = points,
					modifier = Modifier
						.fillMaxWidth()
						.height(120.dp),
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session route preview with 50 points",
		).assertIsDisplayed()
	}

	// endregion

	// region SessionOverviewCard - with path points and map click

	@Test
	fun sessionOverviewCard_withPath_showsViewMapHint() {
		val session = createSession()
		val points = createPoints(3)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = points,
					onMapClick = {},
					snackbarHostState = SnackbarHostState(),
				)
			}
		}

		composeRule.onNodeWithText("View Map", substring = true, useUnmergedTree = true)
			.assertIsDisplayed()
	}

	// endregion
}
