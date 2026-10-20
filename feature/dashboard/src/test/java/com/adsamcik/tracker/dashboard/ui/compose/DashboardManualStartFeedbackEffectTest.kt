package com.adsamcik.tracker.dashboard.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingActionRing
import com.adsamcik.tracker.dashboard.ui.compose.components.TrackingPill
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.dashboard.ui.compose.state.GoalProgressState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardManualStartFeedbackEffectTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Start immediately replaces the Ready action until failure clears it`() {
        var pending by mutableStateOf(false)
        var starts = 0
        composeRule.setContent {
            AppTheme(useDynamicColor = false) {
                DashboardScreen(
                    state = DashboardUiState(hasLocationPermission = true, isStarting = pending),
                    onSettingsClick = {}, onMapClick = {},
                    onToggleTracking = { starts++; pending = true },
                    onRequestPermission = {}, onGameClick = null, onSessionDetailClick = null,
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }
        composeRule.onNodeWithTag("dashboard_empty_list")
            .performScrollToNode(hasTestTag("dashboard_ready_when_you_are_card"))
        composeRule.onNodeWithTag("dashboard_ready_when_you_are_card").performClick()
        composeRule.onNodeWithTag("dashboard_ready_when_you_are_card").assertIsNotEnabled()
        composeRule.onNodeWithText("Preparing tracking").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, starts); pending = false }
        composeRule.onNodeWithTag("dashboard_ready_when_you_are_card").assertIsEnabled()
        composeRule.onNodeWithText("Ready when you are").assertIsDisplayed()
    }

    @Test
    fun `pending Start also disables the daily action and floating pill`() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    TrackingActionRing(false, true, GoalProgressState(), {}, {}, isStarting = true)
                    TrackingPill(true, false, true, null, {}, {}, isStarting = true)
                }
            }
        }
        composeRule.onNodeWithTag("tracking_action_ring").assertIsNotEnabled()
        composeRule.onNodeWithTag("tracking_pill").assertIsNotEnabled()
    }

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
