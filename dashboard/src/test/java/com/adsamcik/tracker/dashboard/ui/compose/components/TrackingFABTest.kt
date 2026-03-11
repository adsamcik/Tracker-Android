package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackingFABTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withoutPermission_clickRequestsPermission() {
		var toggleClicks = 0
		var permissionRequests = 0
		setFabContent(
			isTracking = false,
			hasPermission = false,
			onToggleTracking = { toggleClicks++ },
			onRequestPermission = { permissionRequests++ },
		)

		composeRule.onNodeWithTag("tracking_fab").assertHasClickAction().performClick()

		toggleClicks shouldBe 0
		permissionRequests shouldBe 1
	}

	@Test
	fun idleWithPermission_clickStartsTracking() {
		var toggleClicks = 0
		setFabContent(
			isTracking = false,
			hasPermission = true,
			onToggleTracking = { toggleClicks++ },
			onRequestPermission = {},
		)

		composeRule.onNodeWithTag("tracking_fab").performClick()

		toggleClicks shouldBe 1
	}

	@Test
	fun trackingState_showsStopDescription() {
		setFabContent(
			isTracking = true,
			hasPermission = true,
			onToggleTracking = {},
			onRequestPermission = {},
		)

		composeRule.onNodeWithContentDescription("Stop tracking").assertIsDisplayed()
	}

	private fun setFabContent(
		isTracking: Boolean,
		hasPermission: Boolean,
		onToggleTracking: () -> Unit,
		onRequestPermission: () -> Unit,
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingFAB(
					isTracking = isTracking,
					hasPermission = hasPermission,
					onToggleTracking = onToggleTracking,
					onRequestPermission = onRequestPermission,
				)
			}
		}
	}
}
