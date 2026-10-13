package com.adsamcik.tracker.tracker.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.adsamcik.tracker.shared.base.di.DailyPointsProvider
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.stats.api.repository.AmbientStepsHistoryCause
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayDay
import com.adsamcik.tracker.stats.api.repository.StepsDailyDisplayValue
import com.adsamcik.tracker.stats.api.repository.StepsNumericUnverifiableReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.mockk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackerStepsTodayCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun aDailySubtotalIsLabelledRecordedRatherThanASessionTotal() {
        composeRule.setContent { AppTheme(useDynamicColor = false) {
            TrackerStepsTodayCard(display(StepsDailyDisplayValue.Recorded(18L,
                setOf(AmbientStepsHistoryCause.PARTIAL_COVERAGE))))
        } }
        composeRule.onNodeWithText("Steps today").assertIsDisplayed()
        composeRule.onNodeWithTag("tracker_steps_today_value").assertTextEquals("18 recorded")
    }

    @Test fun aMissingDayIsNotShownAsZero() {
        composeRule.setContent { AppTheme(useDynamicColor = false) {
            TrackerStepsTodayCard(display(StepsDailyDisplayValue.Unavailable(
                StepsNumericUnverifiableReason.NOT_CAPTURED)))
        } }
        composeRule.onNodeWithTag("tracker_steps_today_value").assertTextEquals("No saved steps")
    }

    @Test fun providerStepsSessionHasDailyDataAndNoPhysicalRouteOrDuration() {
        val points = object : DailyPointsProvider {
            override val pointsTodayFlow = MutableStateFlow(0)
        }
        composeRule.setContent { AppTheme(useDynamicColor = false) {
            TrackerDashboard(
                state = TrackerDashboardUiState(
                    isTracking = true,
                    isProviderStepsSession = true,
                    stepsToday = display(StepsDailyDisplayValue.Complete(21L)),
                    trackingParams = TrackingParamsState(stepsEnabled = true),
                ),
                dailyPointsProvider = points,
                dailySummaryProvider = mockk(), goalProgressProvider = mockk(),
                onSettingsClick = {}, onMapClick = {}, onRequestPermission = {},
                onToggleTracking = {}, onPrecisionModeToggle = {},
            )
        } }
        composeRule.onNodeWithTag("tracker_steps_today_value").assertTextEquals("21")
        composeRule.onAllNodesWithText("Distance").assertCountEquals(0)
        composeRule.onAllNodesWithText("Duration").assertCountEquals(0)
        composeRule.onAllNodesWithText("Speed").assertCountEquals(0)
    }

    private fun display(value: StepsDailyDisplayValue) = StepsDailyDisplay.Snapshot(1L,
        listOf(StepsDailyDisplayDay(1L, value)))
}
