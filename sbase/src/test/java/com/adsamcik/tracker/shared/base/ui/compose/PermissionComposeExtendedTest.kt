package com.adsamcik.tracker.shared.base.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.base.permission.ContextualPermissionRequest
import com.adsamcik.tracker.shared.base.permission.PermissionDeniedSnackbar
import com.adsamcik.tracker.shared.base.permission.PermissionType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionDeniedSnackbarTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun `snackbar displays message`() {
		composeRule.setContent {
			MaterialTheme {
				val snackbarHostState = remember { SnackbarHostState() }
				SnackbarHost(hostState = snackbarHostState)
				PermissionDeniedSnackbar(
					snackbarHostState = snackbarHostState,
					message = "Location permission denied",
				)
			}
		}
		composeRule.waitForIdle()
		composeRule.onNodeWithText("Location permission denied").assertIsDisplayed()
	}

	@Test
	fun `snackbar shows settings action`() {
		composeRule.setContent {
			MaterialTheme {
				val snackbarHostState = remember { SnackbarHostState() }
				SnackbarHost(hostState = snackbarHostState)
				PermissionDeniedSnackbar(
					snackbarHostState = snackbarHostState,
					message = "Permission required",
				)
			}
		}
		composeRule.waitForIdle()
		composeRule.onNodeWithText("Open Settings").assertIsDisplayed()
	}

	@Test
	fun `snackbar with custom message shows that message`() {
		composeRule.setContent {
			MaterialTheme {
				val snackbarHostState = remember { SnackbarHostState() }
				SnackbarHost(hostState = snackbarHostState)
				PermissionDeniedSnackbar(
					snackbarHostState = snackbarHostState,
					message = "Activity recognition was denied",
				)
			}
		}
		composeRule.waitForIdle()
		composeRule.onNodeWithText("Activity recognition was denied").assertIsDisplayed()
	}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextualPermissionRequestExtendedTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun `allow and deny buttons displayed for location foreground`() {
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
		composeRule.onNodeWithText("Allow").assertIsDisplayed()
		composeRule.onNodeWithText("Not Now").assertIsDisplayed()
	}

	@Test
	fun `deny button calls both onDismiss and onPermissionResult false for activity recognition`() {
		var dismissCalled = false
		var permissionResult: Boolean? = null

		composeRule.setContent {
			MaterialTheme {
				ContextualPermissionRequest(
					permissionType = PermissionType.ACTIVITY_RECOGNITION,
					permission = android.Manifest.permission.ACTIVITY_RECOGNITION,
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

	@Test
	fun `deny button calls both onDismiss and onPermissionResult false for background location`() {
		var dismissCalled = false
		var permissionResult: Boolean? = null

		composeRule.setContent {
			MaterialTheme {
				ContextualPermissionRequest(
					permissionType = PermissionType.LOCATION_BACKGROUND,
					permission = android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
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
