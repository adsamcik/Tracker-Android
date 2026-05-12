package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.base.di.GoalProgress
import com.adsamcik.tracker.shared.base.di.GoalProgressProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.PolicyTier
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for TrackerDashboard sub-components.
 *
 * Tests for the full [TrackerDashboard] route state and isolated dashboard
 * sub-components (TrackingFAB, LockBanner, PolicyTierChip, TopBar elements).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerDashboardTest {

	@get:Rule
	val composeRule = createComposeRule()

	// region TrackerDashboardUiState defaults

	@Test
	fun uiState_defaultValues() {
		val state = TrackerDashboardUiState()
		assertTrue("Default isTracking should be false", !state.isTracking)
		assertTrue("Default isLocked should be false", !state.isLocked)
		assertTrue("Default sessionData should be null", state.sessionData == null)
		assertTrue("Default collectionData should be null", state.collectionData == null)
		assertTrue("Default hasLocationPermission should be false", !state.hasLocationPermission)
		assertTrue("Default policyTier should be OFF", state.policyTier == PolicyTier.OFF)
		assertTrue(
			"Default precisionModePreset should be BALANCED",
			state.precisionModePreset == TrackingPreset.BALANCED
		)
		assertTrue("Default tracking params should match repository defaults", state.trackingParams == TrackingParamsState())
	}

	@Test
	fun uiState_customValues() {
		val state = TrackerDashboardUiState(
			isTracking = true,
			isLocked = true,
			hasLocationPermission = true,
			policyTier = PolicyTier.PRECISION,
			precisionModePreset = TrackingPreset.HIGH_ACCURACY,
			trackingParams = TrackingParamsState(locationEnabled = false)
		)
		assertTrue("isTracking should be true", state.isTracking)
		assertTrue("isLocked should be true", state.isLocked)
		assertTrue("hasLocationPermission should be true", state.hasLocationPermission)
		assertTrue("policyTier should be PRECISION", state.policyTier == PolicyTier.PRECISION)
		assertTrue(
			"precisionModePreset should be HIGH_ACCURACY",
			state.precisionModePreset == TrackingPreset.HIGH_ACCURACY
		)
		assertTrue("tracking params should be preserved", !state.trackingParams.locationEnabled)
	}

	// endregion

	// region FAB state variants

	@Test
	fun fab_notTracking_withPermission_showsStartContent() {
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

		composeRule.onNodeWithContentDescription("Start tracking").assertIsDisplayed()
		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
	}

	@Test
	fun fab_tracking_showsStopContent() {
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

		composeRule.onNodeWithContentDescription("Stop tracking").assertIsDisplayed()
	}

	@Test
	fun fab_noPermission_callsRequestPermission() {
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
		assertTrue("Should request permission", permissionRequested)
	}

	@Test
	fun fab_withPermission_callsToggleTracking() {
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
		assertTrue("Should toggle tracking", toggled)
	}

	// endregion

	// region LockBanner

	@Test
	fun lockBanner_isVisibleAndClickable() {
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
	fun policyTierChip_highPrecision_displaysLabel() {
		composeRule.setContent {
			AppTheme {
				PolicyTierChip(
					tier = PolicyTier.ACTIVE,
					precisionModePreset = TrackingPreset.HIGH_ACCURACY,
					onClick = {}
				)
			}
		}

		composeRule.onNodeWithText("High Precision").assertIsDisplayed()
	}

	@Test
	fun policyTierChip_batterySaver_displaysLabel() {
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
	fun policyTierChip_clickInvokes() {
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

	// region Points chip (verified in UI via TrackerTopBar)

	@Test
	fun pointsChip_zeroPoints_notDisplayed() {
		val provider = object : DailyPointsProvider {
			override val pointsTodayFlow = MutableStateFlow(0)
		}
		composeRule.setContent {
			AppTheme {
				TrackerTopBar(
					isTracking = false,
					isLocked = false,
					policyTier = PolicyTier.OFF,
					precisionModePreset = TrackingPreset.BALANCED,
					dailyPointsProvider = provider,
					onSettingsClick = {},
					onGameClick = {},
					onPrecisionModeToggle = {},
				)
			}
		}

		// When points are 0, the points chip should not be shown
		composeRule.onNodeWithText("0").assertDoesNotExist()
	}

	@Test
	fun pointsChip_positivePoints_displaysValue() {
		val provider = object : DailyPointsProvider {
			override val pointsTodayFlow = MutableStateFlow(42)
		}
		composeRule.setContent {
			AppTheme {
				TrackerTopBar(
					isTracking = false,
					isLocked = false,
					policyTier = PolicyTier.OFF,
					precisionModePreset = TrackingPreset.BALANCED,
					dailyPointsProvider = provider,
					onSettingsClick = {},
					onGameClick = {},
					onPrecisionModeToggle = {},
				)
			}
		}

		composeRule.onNodeWithText("42").assertIsDisplayed()
	}

	@Test
	fun pointsChip_largePoints_displaysFormattedValue() {
		val provider = object : DailyPointsProvider {
			override val pointsTodayFlow = MutableStateFlow(150)
		}
		composeRule.setContent {
			AppTheme {
				TrackerTopBar(
					isTracking = false,
					isLocked = false,
					policyTier = PolicyTier.OFF,
					precisionModePreset = TrackingPreset.BALANCED,
					dailyPointsProvider = provider,
					onSettingsClick = {},
					onGameClick = {},
					onPrecisionModeToggle = {},
				)
			}
		}

		composeRule.onNodeWithText("150").assertIsDisplayed()
	}

	// endregion
}
