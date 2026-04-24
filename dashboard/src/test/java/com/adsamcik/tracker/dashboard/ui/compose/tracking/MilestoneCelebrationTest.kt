package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MilestoneCelebrationTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withNotTracking_showsNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneCelebrationOverlay(
					sessionData = TrackerSession(
						id = 1L,
						start = System.currentTimeMillis() - 60_000L,
						end = System.currentTimeMillis(),
						distanceInM = 2000f,
						steps = 3000,
					),
					isTracking = false,
				)
			}
		}

		// AnimatedVisibility starts hidden when not tracking
		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
	}

	@Test
	fun withNullSessionData_showsNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneCelebrationOverlay(
					sessionData = null,
					isTracking = true,
				)
			}
		}

		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
	}

	@Test
	fun initialRender_withTracking_showsNothingUntilThresholdCrossed() {
		val now = System.currentTimeMillis()

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				MilestoneCelebrationOverlay(
					sessionData = TrackerSession(
						id = 1L,
						start = now - 300_000L,
						end = now,
						distanceInM = 500f,
						steps = 500,
					),
					isTracking = true,
				)
			}
		}

		// On initial render, lastDistanceKm starts at 0, so the milestone check
		// requires lastDistanceKm > 0 to fire — nothing shows
		composeRule.onAllNodesWithText("km", substring = true).assertCountEquals(0)
		composeRule.onAllNodesWithText("steps", substring = true).assertCountEquals(0)
	}
}
