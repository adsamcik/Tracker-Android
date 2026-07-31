package com.adsamcik.tracker.shared.utils.compose.permission

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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

	private val context: Application
		get() = ApplicationProvider.getApplicationContext()

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

		composeRule.onNodeWithText(context.getString(PermissionType.LOCATION_FOREGROUND.rationaleTitle))
			.assertIsDisplayed()
		composeRule.onNodeWithText(context.getString(com.adsamcik.tracker.shared.utils.R.string.permission_allow))
			.assertIsDisplayed()
		composeRule.onNodeWithText(context.getString(com.adsamcik.tracker.shared.utils.R.string.permission_deny))
			.assertIsDisplayed()
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

		composeRule.onNodeWithText(context.getString(PermissionType.ACTIVITY_RECOGNITION.rationaleTitle))
			.assertIsDisplayed()
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

		composeRule.onNodeWithText(context.getString(PermissionType.LOCATION_BACKGROUND.rationaleTitle))
			.assertIsDisplayed()
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

		composeRule.onNodeWithText(context.getString(com.adsamcik.tracker.shared.utils.R.string.permission_deny))
			.performClick()
		composeRule.waitForIdle()
		assertTrue(dismissCalled)
		assertTrue(permissionResult == false)
	}

	@Test
	fun `background location rationale mentions in the background`() {
		val rationale = context.getString(PermissionType.LOCATION_BACKGROUND.rationaleMessage)

		assertTrue(rationale.contains("in the background"))
	}

	@Test
	fun `background location rationale mentions allow all the time`() {
		val rationale = context.getString(PermissionType.LOCATION_BACKGROUND.rationaleMessage)

		assertTrue(rationale.contains("Allow all the time"))
	}

	@Test
	fun `foreground location rationale says data stays on this device`() {
		val rationale = context.getString(PermissionType.LOCATION_FOREGROUND.rationaleMessage)

		assertTrue(rationale.contains("stays on this device"))
	}

	@Test
	fun `activity recognition rationale says data stays on this device`() {
		val rationale = context.getString(PermissionType.ACTIVITY_RECOGNITION.rationaleMessage)

		assertTrue(rationale.contains("stays on this device"))
	}
}
