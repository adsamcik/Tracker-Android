package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingContentTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withNullSessionData_showsAwaitingGpsText() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingContent(
					state = DashboardUiState(
						isTracking = true,
						sessionData = null,
						collectionData = null,
						pathPoints = null,
					),
					onMapClick = {},
				)
			}
		}

		// No path points and no collection data → "Awaiting GPS signal…"
		composeRule.onNodeWithText("Awaiting GPS signal", substring = true)
			.assertIsDisplayed()
	}

	@Test
	fun withSessionData_showsTrackingActiveText() {
		val now = System.currentTimeMillis()
		val session = TrackerSession(
			id = 1L,
			start = now - 60_000L,
			end = now,
			isUserInitiated = true,
			collections = 5,
			distanceInM = 500f,
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingContent(
					state = DashboardUiState(
						isTracking = true,
						sessionData = session,
						collectionData = null,
						pathPoints = null,
					),
					onMapClick = {},
				)
			}
		}

		composeRule.onNodeWithText("Tracking").assertIsDisplayed()
	}

	@Test
	fun withNullSessionData_doesNotShowStatsGrid() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingContent(
					state = DashboardUiState(
						isTracking = true,
						sessionData = null,
						collectionData = null,
						pathPoints = null,
					),
					onMapClick = {},
				)
			}
		}

		// TrackingStatsGrid uses TrackerR string "Distance" label — should not appear
		composeRule.onAllNodesWithText("Distance", substring = true)
			.assertCountEquals(0)
	}

	@Test
	fun mapHeroCard_firesOnMapClick() {
		var clicked = false
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingContent(
					state = DashboardUiState(
						isTracking = true,
						sessionData = TrackerSession(
							id = 1L,
							start = now - 60_000L,
							end = now,
						),
						collectionData = null,
						pathPoints = null,
					),
					onMapClick = { clicked = true },
				)
			}
		}

		// Tap on the "Tracking" text area (part of MapHeroCard which is clickable)
		composeRule.onNodeWithText("Tracking").performClick()
		assertTrue("onMapClick should be invoked", clicked)
	}
}
