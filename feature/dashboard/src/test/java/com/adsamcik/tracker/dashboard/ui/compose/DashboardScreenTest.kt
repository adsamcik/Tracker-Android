package com.adsamcik.tracker.dashboard.ui.compose

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.utils.style.compose.AppDimensions
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardScreenTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun emptyStateQuickStart_scrollsClearOfFloatingBottomNav() {
		setEmptyDashboardContent()

		composeRule.onNodeWithTag("dashboard_empty_list")
			.performScrollToNode(hasTestTag("dashboard_empty_bottom_clearance"))

		val reviewDescription = composeRule
			.onNodeWithText("After your first session", substring = true)
			.fetchSemanticsNode()
		val bottomNav = composeRule.onNodeWithTag("test_bottom_nav")
			.fetchSemanticsNode()
		val minClearancePx = with(bottomNav.layoutInfo.density) { 16.dp.toPx() }

		assertTrue(
			actual = reviewDescription.boundsInRoot.bottom <= bottomNav.boundsInRoot.top - minClearancePx,
			message = "Expected Quick start review guidance to scroll above the floating bottom nav. " +
				"reviewBounds=${reviewDescription.boundsInRoot}, navBounds=${bottomNav.boundsInRoot}",
		)
	}

	@Test
	fun emptyStateSkippedPermissionGuidance_doesNotReferenceHiddenFabOrButtonBelow() {
		setEmptyDashboardContent()

		composeRule.onAllNodesWithText("floating action button", substring = true)
			.assertCountEquals(0)
		composeRule.onAllNodesWithText("Tap the button below", substring = true)
			.assertCountEquals(0)
		composeRule.onNodeWithTag("dashboard_empty_list")
			.performScrollToNode(hasText("Ready when you are"))
		composeRule.onNodeWithText(
			"Tap here to grant location access, then start your first tracking session.",
		).assertIsDisplayed()
	}

	private fun setEmptyDashboardContent() {
		val phoneConfiguration = Configuration().apply {
			orientation = Configuration.ORIENTATION_PORTRAIT
			screenWidthDp = 412
			screenHeightDp = 800
			smallestScreenWidthDp = 412
		}

		composeRule.setContent {
			CompositionLocalProvider(
				LocalConfiguration provides phoneConfiguration,
				LocalDensity provides Density(density = 1f, fontScale = 1f),
			) {
				AppTheme(useDynamicColor = false) {
					Box(modifier = Modifier.size(width = 412.dp, height = 800.dp)) {
						DashboardScreen(
							state = DashboardUiState(hasLocationPermission = false),
							onSettingsClick = {},
							onMapClick = {},
							onToggleTracking = {},
							onRequestPermission = {},
							onGameClick = null,
							onSessionDetailClick = null,
							snackbarHostState = remember { SnackbarHostState() },
							modifier = Modifier.fillMaxSize(),
						)
						Box(
							modifier = Modifier
								.align(Alignment.BottomCenter)
								.fillMaxWidth()
								.height(AppDimensions.FloatingNavBarClearance)
								.testTag("test_bottom_nav"),
						)
					}
				}
			}
		}
	}
}
