package com.adsamcik.tracker.shared.base.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionType
import androidx.compose.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextualPermissionRequestTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun `shows location rationale dialog`() {
		composeRule.setContent {
			MaterialTheme {
				ContextualPermissionRequest(
					permissionType = PermissionType.LOCATION_FOREGROUND,
					permission = android.Manifest.permission.ACCESS_FINE_LOCATION,
					onPermissionResult = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Location Access Needed").assertIsDisplayed()
		composeRule.onNodeWithText("Allow").assertIsDisplayed()
		composeRule.onNodeWithText("Not Now").assertIsDisplayed()
	}

	@Test
	fun `shows activity recognition rationale dialog`() {
		composeRule.setContent {
			MaterialTheme {
				ContextualPermissionRequest(
					permissionType = PermissionType.ACTIVITY_RECOGNITION,
					permission = android.Manifest.permission.ACTIVITY_RECOGNITION,
					onPermissionResult = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Activity Recognition Needed").assertIsDisplayed()
	}

	@Test
	fun `shows background location rationale dialog`() {
		composeRule.setContent {
			MaterialTheme {
				ContextualPermissionRequest(
					permissionType = PermissionType.LOCATION_BACKGROUND,
					permission = android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
					onPermissionResult = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Background Location Access").assertIsDisplayed()
	}

	@Test
	fun `deny button calls onDismiss and onPermissionResult false`() {
		var dismissCalled = false
		var permissionResult: Boolean? = null

		composeRule.setContent {
			MaterialTheme {
				ContextualPermissionRequest(
					permissionType = PermissionType.LOCATION_FOREGROUND,
					permission = android.Manifest.permission.ACCESS_FINE_LOCATION,
					onPermissionResult = { permissionResult = it },
					onDismiss = { dismissCalled = true },
				)
			}
		}

		composeRule.onNodeWithText("Not Now").performClick()
		composeRule.waitForIdle()
		assertTrue(dismissCalled)
		assertTrue(permissionResult == false)
	}
}
