package com.adsamcik.tracker.shared.utils.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.utils.compose.ConfirmDialog
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmDialogTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun `dialog visible when visible is true`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = true,
					title = "Title",
					message = "Message",
					confirmLabel = "OK",
					dismissLabel = "Cancel",
					onConfirm = {},
					onDismiss = {},
				)
			}
		}
		composeRule.onNodeWithTag("confirm_dialog").assertIsDisplayed()
	}

	@Test
	fun `dialog hidden when visible is false`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = false,
					title = "Title",
					message = "Message",
					confirmLabel = "OK",
					dismissLabel = "Cancel",
					onConfirm = {},
					onDismiss = {},
				)
			}
		}
		composeRule.onNodeWithTag("confirm_dialog").assertDoesNotExist()
	}

	@Test
	fun `confirm button calls onConfirm and onDismiss`() {
		var confirmCalled = false
		var dismissCalled = false

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = true,
					title = "Title",
					message = "Message",
					confirmLabel = "OK",
					dismissLabel = "Cancel",
					onConfirm = { confirmCalled = true },
					onDismiss = { dismissCalled = true },
				)
			}
		}

		composeRule.onNodeWithTag("confirm_dialog_confirm").performClick()
		assertTrue(confirmCalled)
		assertTrue(dismissCalled)
	}

	@Test
	fun `dismiss button calls only onDismiss`() {
		var confirmCalled = false
		var dismissCalled = false

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = true,
					title = "Title",
					message = "Message",
					confirmLabel = "OK",
					dismissLabel = "Cancel",
					onConfirm = { confirmCalled = true },
					onDismiss = { dismissCalled = true },
				)
			}
		}

		composeRule.onNodeWithTag("confirm_dialog_dismiss").performClick()
		assertTrue(dismissCalled)
		assertTrue(!confirmCalled)
	}

	@Test
	fun `title is optional and omitted when null`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = true,
					title = null,
					message = "Message only",
					confirmLabel = "OK",
					dismissLabel = "Cancel",
					onConfirm = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithTag("confirm_dialog").assertIsDisplayed()
		composeRule.onNodeWithText("Message only").assertIsDisplayed()
	}

	@Test
	fun `custom button labels are displayed`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = true,
					title = "Delete?",
					message = "Are you sure?",
					confirmLabel = "Delete",
					dismissLabel = "Keep",
					onConfirm = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Delete").assertIsDisplayed()
		composeRule.onNodeWithText("Keep").assertIsDisplayed()
	}

	@Test
	fun `very long message is displayed`() {
		val longMessage = "A".repeat(500)
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = true,
					title = "Long",
					message = longMessage,
					confirmLabel = "OK",
					dismissLabel = "Cancel",
					onConfirm = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithTag("confirm_dialog").assertIsDisplayed()
	}

	@Test
	fun `dynamic visibility toggle`() {
		var visible by mutableStateOf(false)

		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				ConfirmDialog(
					visible = visible,
					title = "Toggle",
					message = "Body",
					confirmLabel = "Yes",
					dismissLabel = "No",
					onConfirm = {},
					onDismiss = { visible = false },
				)
			}
		}

		composeRule.onNodeWithTag("confirm_dialog").assertDoesNotExist()

		visible = true
		composeRule.waitForIdle()
		composeRule.onNodeWithTag("confirm_dialog").assertIsDisplayed()

		visible = false
		composeRule.waitForIdle()
		composeRule.onNodeWithTag("confirm_dialog").assertDoesNotExist()
	}
}
