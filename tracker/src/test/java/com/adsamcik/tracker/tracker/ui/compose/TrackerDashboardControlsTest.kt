package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.PolicyTier
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardControlsTest {

	@get:Rule
	val composeRule = createComposeRule()

	// region TrackingFAB

	@Test
	fun trackingFAB_notTracking_withPermission_showsStartTracking() {
		composeRule.setContent {
			AppTheme {
				TrackingFAB(
					isTracking = false,
					hasPermission = true,
					onToggleTracking = {},
					onRequestPermission = {}
				)
			}
		}

		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Start tracking").assertIsDisplayed()
	}

	@Test
	fun trackingFAB_tracking_showsStopTracking() {
		composeRule.setContent {
			AppTheme {
				TrackingFAB(
					isTracking = true,
					hasPermission = true,
					onToggleTracking = {},
					onRequestPermission = {}
				)
			}
		}

		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
		composeRule.onNodeWithContentDescription("Stop tracking").assertIsDisplayed()
	}

	@Test
	fun trackingFAB_noPermission_showsPermissionRequired() {
		composeRule.setContent {
			AppTheme {
				TrackingFAB(
					isTracking = false,
					hasPermission = false,
					onToggleTracking = {},
					onRequestPermission = {}
				)
			}
		}

		composeRule.onNodeWithContentDescription("Start tracking - permission required")
			.assertIsDisplayed()
	}

	@Test
	fun trackingFAB_withPermission_clickInvokesToggle() {
		var toggled = false
		composeRule.setContent {
			AppTheme {
				TrackingFAB(
					isTracking = false,
					hasPermission = true,
					onToggleTracking = { toggled = true },
					onRequestPermission = {}
				)
			}
		}

		composeRule.onNodeWithTag("tracking_fab").performClick()
		assertTrue("onToggleTracking should fire when has permission", toggled)
	}

	@Test
	fun trackingFAB_noPermission_clickInvokesRequestPermission() {
		var permissionRequested = false
		composeRule.setContent {
			AppTheme {
				TrackingFAB(
					isTracking = false,
					hasPermission = false,
					onToggleTracking = {},
					onRequestPermission = { permissionRequested = true }
				)
			}
		}

		composeRule.onNodeWithTag("tracking_fab").performClick()
		assertTrue("onRequestPermission should fire when no permission", permissionRequested)
	}

	@Test
	fun trackingFAB_tracking_clickInvokesToggle() {
		var toggled = false
		composeRule.setContent {
			AppTheme {
				TrackingFAB(
					isTracking = true,
					hasPermission = true,
					onToggleTracking = { toggled = true },
					onRequestPermission = {}
				)
			}
		}

		composeRule.onNodeWithTag("tracking_fab").performClick()
		assertTrue("onToggleTracking should fire to stop tracking", toggled)
	}

	// endregion

	// region LockBanner

	@Test
	fun lockBanner_displaysAndIsClickable() {
		var clicked = false
		composeRule.setContent {
			AppTheme {
				LockBanner(onClick = { clicked = true })
			}
		}

		composeRule.onNodeWithTag("lock_banner").assertIsDisplayed()
		composeRule.onNodeWithTag("lock_banner").performClick()
		assertTrue("LockBanner click callback should fire", clicked)
	}

	// endregion

	// region PolicyTierChip

	@Test
	fun policyTierChip_highPrecision_showsLabel() {
		composeRule.setContent {
			AppTheme {
				PolicyTierChip(
					tier = PolicyTier.ACTIVE,
					precisionModePreset = TrackingPreset.HIGH_ACCURACY,
					onClick = {}
				)
			}
		}

		// The chip displays text "High Precision"
		composeRule.onNodeWithText("High Precision").assertIsDisplayed()
	}

	@Test
	fun policyTierChip_batterySaver_showsLabel() {
		composeRule.setContent {
			AppTheme {
				PolicyTierChip(
					tier = PolicyTier.AMBIENT,
					precisionModePreset = TrackingPreset.POWER_SAVE,
					onClick = {}
				)
			}
		}

		composeRule.onNodeWithText("Battery Saver").assertIsDisplayed()
	}

	@Test
	fun policyTierChip_clickInvokesCallback() {
		var clicked = false
		composeRule.setContent {
			AppTheme {
				PolicyTierChip(
					tier = PolicyTier.PRECISION,
					precisionModePreset = TrackingPreset.HIGH_ACCURACY,
					onClick = { clicked = true }
				)
			}
		}

		composeRule.onNodeWithText("High Precision").performClick()
		assertTrue("PolicyTierChip click callback should fire", clicked)
	}

	// endregion
}
