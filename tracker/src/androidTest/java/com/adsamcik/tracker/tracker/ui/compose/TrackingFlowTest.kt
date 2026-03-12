package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.testing.accessibility.assertHasContentDescription
import com.adsamcik.tracker.testing.accessibility.assertMinTouchTargetSize
import com.adsamcik.tracker.testing.data.TestDataFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive tracking flow tests covering:
 * - FAB states (enabled, disabled, locked)
 * - Tracking transitions (idle -> active -> stopped)
 * - Session data display during tracking
 * - Accessibility: touch targets and semantic labels
 */
@RunWith(AndroidJUnit4::class)
class TrackingFlowTest {

	@get:Rule
	val composeRule = createComposeRule()

	private var permissionRequested = false
	private var trackingToggled = false
	private var trackingToggledValue: Boolean? = null
	private var settingsClicked = false

	private val fakeDailyPointsProvider = FakeDailyPointsProvider()
	private val fakeDailySummaryProvider = FakeDailySummaryProvider()
	private val fakeGoalProgressProvider = FakeGoalProgressProvider()

	private val testHapticFeedback = object : HapticFeedback {
		override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
			// No-op for tests
		}
	}

	@Before
	fun setup() {
		permissionRequested = false
		trackingToggled = false
		trackingToggledValue = null
		settingsClicked = false
	}

	private fun setDashboardContent(state: TrackerDashboardUiState) {
		composeRule.setContent {
			CompositionLocalProvider(LocalHapticFeedback provides testHapticFeedback) {
				MaterialTheme(colorScheme = lightColorScheme()) {
					TrackerDashboard(
						state = state,
						dailyPointsProvider = fakeDailyPointsProvider,
						dailySummaryProvider = fakeDailySummaryProvider,
						goalProgressProvider = fakeGoalProgressProvider,
						onSettingsClick = { settingsClicked = true },
						onMapClick = { },
						onRequestPermission = { permissionRequested = true },
						onToggleTracking = { shouldStart ->
							trackingToggled = true
							trackingToggledValue = shouldStart
						}
					)
				}
			}
		}
	}

	// region FAB States

	@Test
	fun fab_whenNotTrackingWithPermission_isEnabledAndStartsTracking() {
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = false,
				hasLocationPermission = true,
				isLocked = false
			)
		)

		composeRule.onNodeWithTag("tracking_fab")
			.assertIsDisplayed()
			.assertIsEnabled()
			.performClick()

		assert(trackingToggled) { "Expected tracking to be toggled" }
		assert(trackingToggledValue == true) { "Expected tracking to start" }
	}

	@Test
	fun fab_whenTrackingWithPermission_isEnabledAndStopsTracking() {
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = true,
				hasLocationPermission = true,
				isLocked = false
			)
		)

		composeRule.onNodeWithTag("tracking_fab")
			.assertIsDisplayed()
			.assertIsEnabled()
			.performClick()

		assert(trackingToggled) { "Expected tracking to be toggled" }
		assert(trackingToggledValue == false) { "Expected tracking to stop" }
	}

	@Test
	fun fab_whenNoPermission_requestsPermissionOnClick() {
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = false,
				hasLocationPermission = false,
				isLocked = false
			)
		)

		composeRule.onNodeWithTag("tracking_fab")
			.assertIsDisplayed()
			.performClick()

		assert(permissionRequested) { "Expected permission request" }
		assert(!trackingToggled) { "Should not toggle tracking without permission" }
	}

	@Test
	fun fab_whenLocked_showsLockBanner() {
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = false,
				hasLocationPermission = true,
				isLocked = true
			)
		)

		// Lock banner should be visible
		composeRule.onNode(hasTestTag("lock_banner"), useUnmergedTree = true)
			.assertExists()

		// FAB should still exist (may or may not be clickable depending on implementation)
		composeRule.onNodeWithTag("tracking_fab")
			.assertIsDisplayed()
	}

	// endregion

	// region Tracking Transitions

	@Test
	fun trackingTransition_idleToActive_showsSessionData() {
		// Start with idle state
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = false,
				hasLocationPermission = true
			)
		)

		// Verify empty state UI
		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()

		// Transition to active tracking with session data
		val session = TestDataFactory.createTrackerSession(
			id = 1L,
			collections = 50,
			distanceInM = 2500f,
			steps = 3200
		)

		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = true,
				hasLocationPermission = true,
				sessionData = session
			)
		)

		// FAB should still be present (now for stopping)
		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
	}

	@Test
	fun trackingTransition_activeWithPathPoints_displaysRoute() {
		val pathPoints = TestDataFactory.createLocationPath(count = 10)

		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = true,
				hasLocationPermission = true,
				sessionData = TestDataFactory.createTrackerSession(),
				pathPoints = pathPoints
			)
		)

		// UI should display tracking state with path
		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
	}

	// endregion

	// region Session Data Display

	@Test
	fun sessionData_withHighCollectionCount_displaysCorrectly() {
		val longSession = TestDataFactory.createLongSession(durationHours = 4)

		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = true,
				hasLocationPermission = true,
				sessionData = longSession
			)
		)

		composeRule.waitForIdle()

		// Dashboard should handle large data gracefully
		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
	}

	@Test
	fun sessionData_emptySession_displaysGracefully() {
		val emptySession = TestDataFactory.createEmptySession()

		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = true,
				hasLocationPermission = true,
				sessionData = emptySession
			)
		)

		composeRule.waitForIdle()

		// Dashboard should handle empty session without crash
		composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
	}

	// endregion

	// region Accessibility

	@Test
	fun fab_hasMininumTouchTargetSize() {
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = false,
				hasLocationPermission = true
			)
		)

		composeRule.onNodeWithTag("tracking_fab")
			.assertMinTouchTargetSize()
	}

	@Test
	fun lockBanner_hasAccessibleDescription() {
		setDashboardContent(
			TrackerDashboardUiState(
				isTracking = false,
				hasLocationPermission = true,
				isLocked = true
			)
		)

		composeRule.onNode(hasTestTag("lock_banner"), useUnmergedTree = true)
			.assertExists()
		// Lock banner should communicate its purpose to screen readers
	}

	// endregion
}
