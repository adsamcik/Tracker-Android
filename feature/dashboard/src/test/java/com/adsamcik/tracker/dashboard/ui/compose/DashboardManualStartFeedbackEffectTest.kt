package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardManualStartFeedbackEffectTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `pending Start shows preparation and lets the user cancel it`() {
        var pending by mutableStateOf(true)
        var cancellations = 0
        val snackbar = SnackbarHostState()
        composeRule.setContent {
            MaterialTheme {
                SnackbarHost(snackbar)
                DashboardManualStartFeedbackEffect(pending, snackbar) {
                    cancellations++
                    pending = false
                }
            }
        }
        composeRule.onNodeWithText("Preparing tracking").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertEquals(1, cancellations) }
        composeRule.onNodeWithText("Preparing tracking").assertDoesNotExist()
    }

    @Test
    fun `a failed Start removes preparation without blocking the error message`() {
        var pending by mutableStateOf(true)
        lateinit var scope: CoroutineScope
        val snackbar = SnackbarHostState()
        composeRule.setContent {
            scope = rememberCoroutineScope()
            MaterialTheme {
                SnackbarHost(snackbar)
                DashboardManualStartFeedbackEffect(pending, snackbar) {}
            }
        }
        composeRule.onNodeWithText("Preparing tracking").assertIsDisplayed()
        composeRule.runOnIdle {
            pending = false
            scope.launch { snackbar.showSnackbar("Tracking could not start") }
        }
        composeRule.onNodeWithText("Preparing tracking").assertDoesNotExist()
        composeRule.onNodeWithText("Tracking could not start").assertIsDisplayed()
    }
}
