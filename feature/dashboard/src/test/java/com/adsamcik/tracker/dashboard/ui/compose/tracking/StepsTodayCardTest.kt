package com.adsamcik.tracker.dashboard.ui.compose.tracking

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.adsamcik.tracker.dashboard.ui.compose.DashboardScreen
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardLiveSessionPresentation
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardMode
import com.adsamcik.tracker.dashboard.ui.compose.state.DashboardUiState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayDay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StepsTodayCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun savedDailyStepsRemainVisibleWhenLiveRadioHistoryFailsWithoutAPhysicalSegment() {
        composeRule.setContent {
            AppTheme(useDynamicColor = false) {
                DashboardScreen(
                    state = DashboardUiState(
                        dashboardMode = DashboardMode.TRACKING,
                        isTracking = true,
                        stepsSelected = true,
                        stepsToday = display(StepsDailyDisplayValue.Recorded(17L,
                            setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE))),
                        liveSessionPresentation = DashboardLiveSessionPresentation.HistoryUnavailable(
                            segmentId = 7L, source = HistorySource.WIFI),
                    ),
                    onSettingsClick = {}, onMapClick = {}, onToggleTracking = {},
                    onRequestPermission = {}, onGameClick = null, onSessionDetailClick = null,
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        composeRule.onNodeWithText("Steps today").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_steps_today_value").assertTextEquals("17 recorded")
    }

    @Test fun completeZeroRemainsAnActualDailyMeasurement() {
        composeRule.setContent { AppTheme(useDynamicColor = false) {
            StepsTodayCard(display(StepsDailyDisplayValue.Complete(0L)))
        } }
        composeRule.onNodeWithTag("dashboard_steps_today_value").assertTextEquals("0")
        composeRule.onNodeWithText("Steps today").assertIsDisplayed()
    }

    @Test fun providerStepsSessionShowsDailyDataWithoutInventedPhysicalMetrics() {
        composeRule.setContent { AppTheme(useDynamicColor = false) {
            DashboardScreen(
                state = DashboardUiState(
                    dashboardMode = DashboardMode.TRACKING,
                    isTracking = true,
                    isProviderStepsSession = true,
                    stepsSelected = true,
                    stepsToday = display(StepsDailyDisplayValue.Complete(23L)),
                ),
                onSettingsClick = {}, onMapClick = {}, onToggleTracking = {},
                onRequestPermission = {}, onGameClick = null, onSessionDetailClick = null,
                snackbarHostState = remember { SnackbarHostState() },
            )
        } }
        composeRule.onNodeWithTag("dashboard_steps_today_value").assertTextEquals("23")
        composeRule.onAllNodesWithText("Preparing live session details", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Distance").assertCountEquals(0)
        composeRule.onAllNodesWithText("Speed").assertCountEquals(0)
    }

    private fun display(value: StepsDailyDisplayValue) = StepsDailyDisplay.Snapshot(1L,
        listOf(StepsDailyDisplayDay(1L, value)))
}
