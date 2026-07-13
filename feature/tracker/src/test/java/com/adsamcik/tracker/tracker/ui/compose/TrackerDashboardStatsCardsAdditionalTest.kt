package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.model.Location
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Additional tests for StatusAndQuickStatsCard covering:
 * - Tab switching (LIVE, ROUTE, ACTIVITY)
 * - Content in different tabs
 * - Session with zero collections
 * - Path points distance derivation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardStatsCardsAdditionalTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun createSession(
		distanceInM: Float = 2500f,
		steps: Int = 3000,
		collections: Int = 50,
		start: Long = Time.nowMillis - 1800_000L,
	) = TrackerSessionSnapshot(
		id = 1L,
		start = start,
		end = Time.nowMillis,
		isUserInitiated = true,
		collections = collections,
		distanceInM = distanceInM,
		distanceOnFootInM = distanceInM * 0.7f,
		distanceInVehicleInM = distanceInM * 0.3f,
		steps = steps,
	)

	// region Tab switching

	@Test
	fun statusCard_defaultTab_showsLiveContent() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				StatusAndQuickStatsCard(
					isTracking = true,
					sessionData = session,
					collectionData = null,
					wallClockNowMillis = Time.nowMillis,
					onMapClick = {},
				)
			}
		}

		// Default tab is LIVE; speed and average labels should be present
		composeRule.onNodeWithText("Speed", substring = true).assertIsDisplayed()
	}

	@Test
	fun statusCard_switchToRouteTab_showsRouteContent() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				StatusAndQuickStatsCard(
					isTracking = true,
					sessionData = session,
					collectionData = null,
					wallClockNowMillis = Time.nowMillis,
					onMapClick = {},
				)
			}
		}

		// Click on Route tab
		composeRule.onNodeWithText("Route", substring = true).performClick()

		// Route tab should show distance and altitude labels
		composeRule.onNodeWithText("Distance", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("Altitude", substring = true).assertIsDisplayed()
	}

	@Test
	fun statusCard_switchToActivityTab_showsActivityContent() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				StatusAndQuickStatsCard(
					isTracking = true,
					sessionData = session,
					collectionData = null,
					wallClockNowMillis = Time.nowMillis,
					onMapClick = {},
				)
			}
		}

		// Click on Activity tab
		composeRule.onNodeWithText("Activity", substring = true).performClick()

		// Activity tab should show steps label
		composeRule.onNodeWithText("Steps", substring = true).assertIsDisplayed()
	}

	// endregion

	// region Zero/edge cases

	@Test
	fun statusCard_zeroCollections_rendersWithoutCrash() {
		val session = createSession(
			distanceInM = 0f,
			steps = 0,
			collections = 0,
		)

		composeRule.setContent {
			AppTheme {
				StatusAndQuickStatsCard(
					isTracking = true,
					sessionData = session,
					collectionData = null,
					wallClockNowMillis = Time.nowMillis,
					onMapClick = {},
				)
			}
		}

		composeRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
	}

	@Test
	fun statusCard_withPathPoints_rendersWithoutCrash() {
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
				speedAccuracy = 0.5f,
			),
			Location(
				time = session.start + 60_000L,
				latitude = 50.001,
				longitude = 14.001,
				altitude = 201.0,
				horizontalAccuracy = 4f,
				verticalAccuracy = 3f,
				speed = 1.6f,
				speedAccuracy = 0.5f,
			),
		)

		composeRule.setContent {
			AppTheme {
				StatusAndQuickStatsCard(
					isTracking = true,
					sessionData = session,
					collectionData = null,
					wallClockNowMillis = Time.nowMillis,
					onMapClick = {},
					pathPoints = points,
				)
			}
		}

		composeRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
	}

	// endregion

	// region Not-tracking state

	@Test
	fun statusCard_notTracking_showsDurationAsPrimary() {
		val session = createSession()
		composeRule.setContent {
			AppTheme {
				StatusAndQuickStatsCard(
					isTracking = false,
					sessionData = session,
					collectionData = null,
					wallClockNowMillis = Time.nowMillis,
					onMapClick = {},
				)
			}
		}

		// When not tracking (not moving), duration should be the primary metric label
		composeRule.onNodeWithText("Session duration", substring = true).assertIsDisplayed()
	}

	// endregion
}
