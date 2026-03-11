package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DashboardScreenTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyMode_rendersEmptyStateCard() {
		setDashboardContent(DashboardUiState(dashboardMode = DashboardMode.EMPTY))

		composeRule.onNodeWithText("Your Journey Starts Here").assertIsDisplayed()
	}

	@Test
	fun idleMode_rendersTodayProgressContent() {
		setDashboardContent(DashboardUiState(dashboardMode = DashboardMode.IDLE))

		composeRule.onNodeWithText("Today").assertIsDisplayed()
		composeRule.onNodeWithText("No activity yet today").assertIsDisplayed()
	}

	@Test
	fun idleMode_withNoTrips_rendersRecentTripsEmptyState() {
		setDashboardContent(DashboardUiState(dashboardMode = DashboardMode.IDLE))

		composeRule.onNodeWithText("Recent Trips").assertIsDisplayed()
		composeRule.onNodeWithText(
			"No recent trips. Start tracking to see your journeys here.",
		).assertIsDisplayed()
	}

	@Test
	fun trackingMode_rendersTrackingContent() {
		setDashboardContent(
			DashboardUiState(
				dashboardMode = DashboardMode.TRACKING,
				isTracking = true,
				hasLocationPermission = true,
			),
		)

		// The tracking pill is visible in tracking mode
		composeRule.onNodeWithTag("tracking_pill").assertIsDisplayed()
	}

	private fun setDashboardContent(state: DashboardUiState) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				DashboardScreen(
					state = state,
					onSettingsClick = {},
					onMapClick = {},
					onToggleTracking = {},
					onRequestPermission = {},
					onGameClick = null,
					onSessionDetailClick = null,
					snackbarHostState = SnackbarHostState(),
				)
			}
		}
	}
}
