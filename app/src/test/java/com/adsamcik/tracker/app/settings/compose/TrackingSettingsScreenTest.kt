package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.tracking.TrackingSettingsContent
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.EstimateConfidence
import com.adsamcik.tracker.tracker.source.battery.EstimateTarget
import com.adsamcik.tracker.tracker.source.battery.EvidenceSource
import com.adsamcik.tracker.tracker.source.battery.ImpactAssumption
import com.adsamcik.tracker.tracker.source.battery.ImpactDriver
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the real [TrackingSettingsContent] composable.
 * Uses the extracted content composable with injectable state to verify
 * UI rendering and interactions without requiring Hilt/ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultUiState = TrackingSettingsUiState(
        isLoaded = true,
        currentPreset = TrackingPreset.BALANCED,
        currentBatteryImpact = BatteryImpact.MODERATE,
        locationEnabled = true,
        activityEnabled = true,
        stepsEnabled = true,
        wifiEnabled = true,
        cellEnabled = false,
        transitionDetectionEnabled = true,
        notificationStyled = true,
        minDistance = 10,
        minTime = 2,
        requiredAccuracy = 50,
        hasValidSources = true,
    )

    private fun scrollTo(text: String) {
        composeTestRule.onNodeWithTag("trackingSettingsList")
            .performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun displaysPresetSelector() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        scrollTo("Balanced")
        composeTestRule.onNodeWithText("Balanced", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysTrackingNotice() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        composeTestRule.onNodeWithText("Changes take effect", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysToggleSettings() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        scrollTo("Use activity transitions")
        composeTestRule.onNodeWithText("Use activity transitions", substring = true).assertIsDisplayed()
        scrollTo("Colored notifications")
        composeTestRule.onNodeWithText("Colored notifications", substring = true).assertIsDisplayed()
    }

    @Test
    fun transitionDetectionToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onTransitionDetectionChanged = { newValue = it },
                )
            }
        }
        scrollTo("Use activity transitions")
        composeTestRule.onNodeWithText("Use activity transitions", substring = true).performClick()
        newValue shouldBe false // Was true, toggling makes false
    }

    @Test
    fun notificationToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onNotificationStyledChanged = { newValue = it },
                )
            }
        }
        scrollTo("Colored notifications")
        composeTestRule.onNodeWithText("Colored notifications", substring = true).performClick()
        newValue shouldBe false // Was true, toggling makes false
    }

    @Test
    fun validationWarningShownWhenNoValidSources() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(hasValidSources = false),
                )
            }
        }
        composeTestRule.onNodeWithText("Enable at least one tracking", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun validationWarningHiddenWhenSourcesValid() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(hasValidSources = true),
                )
            }
        }
        composeTestRule.onNodeWithText("Enable at least one tracking", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun settingsOrganizedIntoVisibleSections() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        // Settings are no longer hidden behind a collapsed "Advanced" section — each
        // group has a visible header and its controls are reachable by scrolling.
        scrollTo("Data sources")
        composeTestRule.onNodeWithText("Data sources", substring = true).assertIsDisplayed()
        scrollTo("Location collection")
        composeTestRule.onNodeWithText("Location collection", substring = true).assertIsDisplayed()
    }

    @Test
    fun dataSourceTogglesVisibleWithoutExpanding() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        scrollTo("Location")
        composeTestRule.onNodeWithText("Location").assertIsDisplayed()
        scrollTo("Activity")
        composeTestRule.onNodeWithText("Activity").assertIsDisplayed()
        scrollTo("Barometer")
        composeTestRule.onNodeWithText("Barometer").assertIsDisplayed()
    }

    @Test
    fun effectiveStatusExplainsThatIdlePreviewIsNotAnAppliedPlan() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }

        scrollTo("Requested and effective status")
        composeTestRule.onNodeWithTag("effectiveTrackingStatus").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tracking is not active", substring = true).assertIsDisplayed()
    }

    @Test
    fun advancedSourceControlPublishesSemanticFrequencyChoice() {
        var selected: Pair<TrackingSourceComponent, SourceCollectionFrequency>? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        advancedSourceControlsEnabled = true,
                        sourceCollectionSettings = SourceCollectionSettings(),
                    ),
                    onSourceFrequencyChanged = { source, frequency -> selected = source to frequency },
                )
            }
        }

        scrollTo("Requested: Balanced")
        composeTestRule.onNodeWithTag("sourceFrequency-Location").performClick()
        composeTestRule.onNodeWithText("Responsive").performClick()
        selected shouldBe (TrackingSourceComponent.LOCATION to SourceCollectionFrequency.RESPONSIVE)
    }

    @Test
    fun batteryCardShowsConfidenceAndDoesNotInventHoursOrPercentages() {
        val estimate = BatteryImpactEstimate(
            level = ImpactLevel.MODERATE,
            estimatedPercentPerHour = null,
            estimateTarget = EstimateTarget.QUALITATIVE_RELATIVE_TRACKER_IMPACT,
            candidatePlanId = "candidate",
            comparisonBaselineId = "balanced",
            evidenceSource = EvidenceSource.GENERIC_PRIOR,
            sampleCount = 0,
            observationDurationMs = 0,
            confidence = EstimateConfidence.LOW,
            uncertainty = null,
            dominantDrivers = listOf(ImpactDriver.LOCATION),
            assumptions = listOf(ImpactAssumption("generic_device_prior")),
            calibrationVersion = 0,
        )
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(uiState = defaultUiState.copy(batteryEstimate = estimate))
            }
        }

        scrollTo("Estimated tracking impact")
        composeTestRule.onNodeWithTag("batteryEstimateCard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confidence: Low").assertIsDisplayed()
        scrollTo("No trustworthy percentage")
        composeTestRule.onNodeWithText("No trustworthy percentage", substring = true).assertIsDisplayed()
    }

    @Test
    fun activeStatusShowsPayloadFreeRuntimeTelemetry() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        trackingActive = true,
                        desiredPlanRevision = 8L,
                        appliedPlanRevision = 8L,
                        runtimeTelemetry = TrackingCoordinatorMetrics.ZERO.copy(
                            projectedEventCount = 9L,
                            planRevisionCount = 2L,
                        ),
                    ),
                )
            }
        }

        scrollTo("Local runtime telemetry")
        composeTestRule.onNodeWithText("Projected events: 9", substring = true).assertIsDisplayed()
    }

    @Test
    fun locationControlsHiddenWhenLocationSourceIsDisabled() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(uiState = defaultUiState.copy(locationEnabled = false))
            }
        }

        composeTestRule.onNodeWithText("Location collection", substring = true).assertDoesNotExist()
    }

    @Test
    fun notLoadedShowsNothing() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(isLoaded = false),
                )
            }
        }
        composeTestRule.onNodeWithText("Balanced", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Use activity transitions", substring = true)
            .assertDoesNotExist()
    }
}
