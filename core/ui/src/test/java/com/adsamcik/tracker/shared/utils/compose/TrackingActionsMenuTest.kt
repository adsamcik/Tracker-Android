package com.adsamcik.tracker.shared.utils.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingActionsMenuTest {
	@get:Rule val composeRule = createComposeRule()

	@Test
	fun turnOffIsAvailableWithoutASessionOrAutomaticStarts() {
		var off = 0
		composeRule.setContent {
			MaterialTheme {
				TrackingActionsMenu("Tracking actions", "Turn off tracking", { off++ })
			}
		}
		composeRule.onNodeWithTag("trackingActionsMenu").performClick()
		composeRule.onNodeWithTag("pauseAutomaticTracking").assertDoesNotExist()
		composeRule.onNodeWithTag("turnOffTracking").assertIsDisplayed().performClick()
		assertEquals(1, off)
		composeRule.onNodeWithTag("turnOffTracking").assertDoesNotExist()
	}

	@Test
	fun pauseAndOffAreSeparateActions() {
		var off = 0
		var pause = 0
		composeRule.setContent {
			MaterialTheme {
				TrackingActionsMenu("Tracking actions", "Turn off tracking", { off++ },
					"Pause automatic starts", { pause++ })
			}
		}
		composeRule.onNodeWithTag("trackingActionsMenu").performClick()
		composeRule.onNodeWithTag("pauseAutomaticTracking").performClick()
		assertEquals(1, pause)
		assertEquals(0, off)
		composeRule.onNodeWithTag("trackingActionsMenu").performClick()
		composeRule.onNodeWithTag("turnOffTracking").performClick()
		assertEquals(1, off)
		assertEquals(1, pause)
	}
}
