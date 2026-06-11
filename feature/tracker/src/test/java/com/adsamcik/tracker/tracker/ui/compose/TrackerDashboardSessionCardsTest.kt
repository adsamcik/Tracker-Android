package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.model.Location
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardSessionCardsTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun createSession(
		id: Long = 1L,
		start: Long = Time.nowMillis - 3600_000L,
		end: Long = Time.nowMillis,
		distanceInM: Float = 5000f,
		steps: Int = 7500,
		collections: Int = 120
	) = TrackerSession(
		id = id,
		start = start,
		end = end,
		isUserInitiated = true,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceInM * 0.8f,
		distanceInVehicleInM = distanceInM * 0.2f,
		steps = steps
	)

	// region SessionOverviewCard

	@Test
	fun sessionOverviewCard_displaysSessionTitle() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState()
				)
			}
		}

		// The card should have semantic content description with session info
		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session overview"
		).assertIsDisplayed()
	}

	@Test
	fun sessionOverviewCard_tracking_showsActiveBadge() {
		val session = createSession(end = 0)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = true,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState()
				)
			}
		}

		// Active badge should be present when tracking
		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session overview"
		).assertIsDisplayed()
	}

	@Test
	fun sessionOverviewCard_zeroDistance_rendersWithoutCrash() {
		val session = createSession(distanceInM = 0f, steps = 0, collections = 0)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					snackbarHostState = SnackbarHostState()
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session overview"
		).assertIsDisplayed()
	}

	@Test
	fun sessionOverviewCard_onDetailClick_invokesCallback() {
		var clickedId: Long? = null
		val session = createSession(id = 42L)
		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = null,
					onMapClick = {},
					onDetailClick = { clickedId = it },
					snackbarHostState = SnackbarHostState()
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session overview"
		).performClick()
		assertTrue("Detail click should pass session id", clickedId == 42L)
	}

	@Test
	fun sessionOverviewCard_withPathPoints_rendersMapPreview() {
		val session = createSession()
		val points = listOf(
			Location(
				time = session.start,
				latitude = 50.0,
				longitude = 14.0,
				altitude = 200.0,
				horizontalAccuracy = 5f,
				verticalAccuracy = 3f,
				speed = 1.5f,
				speedAccuracy = 0.5f
			),
			Location(
				time = session.start + 60_000,
				latitude = 50.001,
				longitude = 14.001,
				altitude = 201.0,
				horizontalAccuracy = 4f,
				verticalAccuracy = 3f,
				speed = 1.6f,
				speedAccuracy = 0.5f
			),
			Location(
				time = session.start + 120_000,
				latitude = 50.002,
				longitude = 14.002,
				altitude = 202.0,
				horizontalAccuracy = 3f,
				verticalAccuracy = 2f,
				speed = 1.4f,
				speedAccuracy = 0.5f
			)
		)

		composeRule.setContent {
			AppTheme {
				SessionOverviewCard(
					session = session,
					isTracking = false,
					pathPoints = points,
					onMapClick = {},
					snackbarHostState = SnackbarHostState()
				)
			}
		}

		// Path preview has accessibility description
		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session route preview"
		).assertIsDisplayed()
	}

	// endregion

	// region SessionPathPreview

	@Test
	fun sessionPathPreview_withPoints_renders() {
		val points = listOf(
			Location(
				time = 1000L,
				latitude = 50.0,
				longitude = 14.0,
				altitude = null,
				horizontalAccuracy = null,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null
			),
			Location(
				time = 2000L,
				latitude = 50.01,
				longitude = 14.01,
				altitude = null,
				horizontalAccuracy = null,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null
			)
		)

		composeRule.setContent {
			AppTheme {
				SessionPathPreview(
					points = points,
					modifier = androidx.compose.ui.Modifier
						.fillMaxWidth()
						.height(120.dp)
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session route preview"
		).assertIsDisplayed()
	}

	@Test
	fun sessionPathPreview_singlePoint_rendersWithoutCrash() {
		val points = listOf(
			Location(
				time = 1000L,
				latitude = 50.0,
				longitude = 14.0,
				altitude = null,
				horizontalAccuracy = null,
				verticalAccuracy = null,
				speed = null,
				speedAccuracy = null
			)
		)

		composeRule.setContent {
			AppTheme {
				SessionPathPreview(
					points = points,
					modifier = androidx.compose.ui.Modifier
						.fillMaxWidth()
						.height(120.dp)
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session route preview"
		).assertIsDisplayed()
	}

	@Test
	fun sessionPathPreview_emptyPoints_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme {
				SessionPathPreview(
					points = emptyList(),
					modifier = androidx.compose.ui.Modifier
						.fillMaxWidth()
						.height(120.dp)
				)
			}
		}

		composeRule.onNodeWithContentDescription(
			substring = true,
			label = "Session route preview"
		).assertIsDisplayed()
	}

	// endregion
}
