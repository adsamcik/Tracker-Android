package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LastSessionCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun makeSession(
		distanceInM: Float = 1500f,
		steps: Int = 3000,
		durationMs: Long = 1_800_000L,
	): TrackerSessionSnapshot {
		val now = System.currentTimeMillis()
		return TrackerSessionSnapshot(
			id = 1L,
			start = now - durationMs,
			end = now,
			isUserInitiated = true,
			collections = 20,
			distanceInM = distanceInM,
			steps = steps,
		)
	}

	private fun makeLocation(lat: Double, lon: Double) = Location(
		time = System.currentTimeMillis(),
		latitude = lat,
		longitude = lon,
		altitude = null,
		horizontalAccuracy = null,
		verticalAccuracy = null,
		speed = null,
		speedAccuracy = null,
	)

	@Test
	fun rawPositiveStepsAreOmittedWhileDistanceAndDurationRemain() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LastSessionCard(
					session = makeSession(distanceInM = 1500f, steps = 3000, durationMs = 1_800_000L),
					pathPoints = null,
					onMapClick = {},
				)
			}
		}

		composeRule.onNodeWithText("Last Session").assertIsDisplayed()
		composeRule.onNodeWithText("Duration").assertIsDisplayed()
		composeRule.onNodeWithText("Distance").assertIsDisplayed()
		// 1,800,000ms = 30 min → "30 m" (exact match avoids collision with relative time)
		composeRule.onNodeWithText("30 m").assertIsDisplayed()
		// 1500m → "1.5 km" in metric
		composeRule.onNodeWithText("km", substring = true).assertIsDisplayed()
		composeRule.onAllNodesWithText("Steps").assertCountEquals(0)
	}

	@Test
	fun rawZeroStepsAreAlsoOmitted() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LastSessionCard(
					session = makeSession(steps = 0),
					pathPoints = null,
					onMapClick = {},
				)
			}
		}

		composeRule.onAllNodesWithText("Steps").assertCountEquals(0)
	}

	@Test
	fun withPathPoints_showsViewMapOverlay() {
		val points = listOf(
			makeLocation(50.0, 14.0),
			makeLocation(50.001, 14.001),
			makeLocation(50.002, 14.002),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LastSessionCard(
					session = makeSession(),
					pathPoints = points,
					onMapClick = {},
				)
			}
		}

		composeRule.onNodeWithText("View Map").assertIsDisplayed()
	}

	@Test
	fun withNullPathPoints_hidesViewMap() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LastSessionCard(
					session = makeSession(),
					pathPoints = null,
					onMapClick = {},
				)
			}
		}

		composeRule.onAllNodesWithText("View Map").assertCountEquals(0)
	}

	@Test
	fun withPathPoints_onMapClickFires() {
		var clicked = false
		val points = listOf(
			makeLocation(50.0, 14.0),
			makeLocation(50.001, 14.001),
		)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				LastSessionCard(
					session = makeSession(),
					pathPoints = points,
					onMapClick = { clicked = true },
				)
			}
		}

		composeRule.onNodeWithText("View Map").performClick()
		assertTrue("onMapClick should be invoked", clicked)
	}
}
