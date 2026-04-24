package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingStatsGridTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withBasicSession_showsDistanceLabel() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSession(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 1500f,
						collections = 10,
					),
					collectionData = null,
				)
			}
		}

		// The grid should show the distance label from TrackerR
		composeRule.onNodeWithText("Distance", substring = true).assertIsDisplayed()
	}

	@Test
	fun withSteps_showsStepsLabel() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSession(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 500f,
						steps = 2500,
						collections = 10,
					),
					collectionData = null,
				)
			}
		}

		composeRule.onNodeWithText("Steps", substring = true).assertIsDisplayed()
	}

	@Test
	fun withNullCollectionData_rendersWithoutCrash() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSession(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 0f,
					),
					collectionData = null,
				)
			}
		}

		// Should render without crash even with null collectionData
		composeRule.onNodeWithText("Distance", substring = true).assertIsDisplayed()
	}

	@Test
	fun withZeroSteps_hidesStepsRow() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSession(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 100f,
						steps = 0,
					),
					collectionData = null,
				)
			}
		}

		// Steps row is only shown when steps > 0
		composeRule.onNodeWithText("Steps").assertDoesNotExist()
	}
}
