package com.adsamcik.tracker.shared.utils.compose

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/**
 * UI tests for [ConfirmDialog]. Verifies visibility toggling and callback ordering.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmDialogTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialog_not_visible_when_flag_false() {
        composeRule.setContent {
            ConfirmDialog(
                visible = false,
                title = "Title",
                message = "Message",
                confirmLabel = "Yes",
                dismissLabel = "No",
                onConfirm = {},
                onDismiss = {},
            )
        }
    composeRule.onNodeWithTag("confirm_dialog").assertIsNotDisplayed()
    }

    @Test
    fun confirm_triggers_confirm_then_dismiss() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            val visible = remember { mutableStateOf(true) }
            ConfirmDialog(
                visible = visible.value,
                title = null,
                message = "Proceed?",
                confirmLabel = "OK",
                dismissLabel = "Cancel",
                onConfirm = { events += "confirm" },
                onDismiss = { events += "dismiss"; visible.value = false },
            )
        }
        composeRule.onNodeWithTag("confirm_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm_dialog_confirm").performClick()
        // After click dialog should disappear and ordering confirm -> dismiss
    composeRule.onNodeWithTag("confirm_dialog").assertIsNotDisplayed()
        assertEquals(listOf("confirm", "dismiss"), events)
    }

    @Test
    fun dismiss_without_confirm_only_calls_dismiss() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            val visible = remember { mutableStateOf(true) }
            ConfirmDialog(
                visible = visible.value,
                title = "Danger",
                message = "Delete?",
                confirmLabel = "Delete",
                dismissLabel = "Cancel",
                onConfirm = { events += "confirm" },
                onDismiss = { events += "dismiss"; visible.value = false },
            )
        }
        composeRule.onNodeWithTag("confirm_dialog_dismiss").performClick()
    composeRule.onNodeWithTag("confirm_dialog").assertIsNotDisplayed()
        assertEquals(listOf("dismiss"), events)
    }
}
