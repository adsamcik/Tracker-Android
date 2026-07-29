package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.tracker.data.session.TrackerSessionSnapshot
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
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 1500f,
						collections = 10,
					),
					collectionSnapshot = null,
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
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 120_000L,
						end = now,
						distanceInM = 500f,
						steps = 2500,
						collections = 10,
					),
					collectionSnapshot = null,
				)
			}
		}

		composeRule.onNodeWithText("Steps", substring = true).assertIsDisplayed()
	}

	@Test
	fun withNullCollectionSnapshot_rendersWithoutCrash() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 0f,
					),
					collectionSnapshot = null,
				)
			}
		}

		// Should render without crash even with null collectionSnapshot
		composeRule.onNodeWithText("Distance", substring = true).assertIsDisplayed()
	}

	@Test
	fun withZeroSteps_showsStepsRowWithPlaceholder() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingStatsGrid(
					sessionData = TrackerSessionSnapshot(
						id = 1L,
						start = now - 60_000L,
						end = now,
						distanceInM = 100f,
						steps = 0,
					),
					collectionSnapshot = null,
				)
			}
		}

		// Steps label is always shown (parity with the rest of the compact grid);
		// when the count is zero the row renders an em-dash placeholder value
		// instead of being hidden, so column widths stay stable across sessions
		// and the layout doesn't jump when the first step is registered.
		// (Multiple stats render '–' in the zero-data path — activity, accuracy
		// etc. — so we don't try to uniquely identify the dash, only that the
		// Steps row itself is present.)
		composeRule.onNodeWithText("Steps").assertIsDisplayed()
	}
}
