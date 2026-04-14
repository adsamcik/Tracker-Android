package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingPillTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun visibleIdleWithPermission_showsStart() {
		setContent(visible = true, isTracking = false, hasPermission = true)

		composeRule.onNodeWithTag("tracking_pill").assertIsDisplayed()
		composeRule.onNodeWithText("Start").assertIsDisplayed()
	}

	@Test
	fun visibleTracking_showsStop() {
		setContent(visible = true, isTracking = true, hasPermission = true)

		composeRule.onNodeWithTag("tracking_pill").assertIsDisplayed()
		composeRule.onNodeWithText("Stop").assertIsDisplayed()
	}

	@Test
	fun noPermission_showsEnableLocation() {
		setContent(visible = true, isTracking = false, hasPermission = false)

		composeRule.onNodeWithTag("tracking_pill").assertIsDisplayed()
		composeRule.onNodeWithText("Enable location").assertIsDisplayed()
	}

	@Test
	fun clickWithoutPermission_routesToPermission() {
		var permissionRequested = false
		var toggleCalled = false

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingPill(
					visible = true,
					isTracking = false,
					hasPermission = false,
					sessionData = null,
					onToggleTracking = { toggleCalled = true },
					onRequestPermission = { permissionRequested = true },
				)
			}
		}

		composeRule.onNodeWithTag("tracking_pill").assertIsDisplayed()
	}

	private fun setContent(
		visible: Boolean,
		isTracking: Boolean,
		hasPermission: Boolean,
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				TrackingPill(
					visible = visible,
					isTracking = isTracking,
					hasPermission = hasPermission,
					sessionData = null,
					onToggleTracking = {},
					onRequestPermission = {},
				)
			}
		}
	}
}
