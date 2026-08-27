package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.tracking.TrackingSettingsContent
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.EstimateConfidence
import com.adsamcik.tracker.tracker.source.battery.EstimateTarget
import com.adsamcik.tracker.tracker.source.battery.EvidenceSource
import com.adsamcik.tracker.tracker.source.battery.ImpactAssumption
import com.adsamcik.tracker.tracker.source.battery.ImpactDriver
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.coordinator.TrackingCoordinatorMetrics
import com.adsamcik.tracker.tracker.source.coordinator.SemanticAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanEnvironment
import com.adsamcik.tracker.tracker.source.model.LocationBackend
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
        autoTrackingMode = 1,
        autoTrackingEnabled = true,
        transitionDetectionEnabled = true,
        notificationStyled = true,
        minDistance = 10,
        minTime = 2,
        requiredAccuracy = 50,
        hasValidSources = true,
		sourcePolicyAvailable = true,
		sourcePolicyRevision = 1L,
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
	fun unavailablePolicyShowsAnHonestNonEditableState() {
		composeTestRule.setContent {
			AppTheme {
				TrackingSettingsContent(
					uiState = defaultUiState.copy(
						sourcePolicyAvailable = false,
						sourcePolicyRevision = null,
					),
				)
			}
		}

		composeTestRule.onNodeWithTag("trackingSettingsUnavailable").assertIsDisplayed()
		composeTestRule.onNodeWithText("Tracking settings unavailable").assertIsDisplayed()
		composeTestRule.onNodeWithTag("trackingSettingsList").assertDoesNotExist()
	}

    @Test
    fun trackingNoticeIsHiddenWhileIdle() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        composeTestRule.onNodeWithText("Changes take effect", substring = true).assertDoesNotExist()
    }

    @Test
    fun trackingNoticeAppearsDuringAnActiveTrip() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(uiState = defaultUiState.copy(trackingActive = true))
            }
        }
        composeTestRule.onNodeWithText("Changes take effect", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysToggleSettings() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }
        scrollTo("Battery-efficient motion detection")
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true).assertIsDisplayed()
        scrollTo("Colored notifications")
        composeTestRule.onNodeWithText("Colored notifications", substring = true).assertIsDisplayed()
    }

    @Test
    fun automaticTrackingModeIsVisibleAndExplainsCurrentBehavior() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsContent(uiState = defaultUiState) }
        }

        scrollTo("Automatic tracking")
        composeTestRule.onNodeWithTag("automaticTrackingMode").assertIsDisplayed()
        scrollTo("Automatically track walking and running.")
        composeTestRule.onNodeWithText("Automatically track walking and running.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun automaticTrackingModeSelectorChangesModeDirectly() {
        var selectedMode: Int? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onAutoTrackingModeChanged = { selectedMode = it },
                )
            }
        }

        scrollTo("Automatically track all movement")
        composeTestRule.onNodeWithTag("automaticTrackingModeOption-2").performClick()
        composeTestRule.waitForIdle()

        selectedMode shouldBe 2
    }

    @Test
    fun transitionDetectionIsHiddenWhenAutomaticTrackingIsDisabled() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        autoTrackingMode = 0,
                        autoTrackingEnabled = false,
                    ),
                )
            }
        }

        scrollTo("Automatic tracking")
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("Start and stop tracking manually.", substring = true)
            .assertIsDisplayed()
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
        scrollTo("Battery-efficient motion detection")
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true).performClick()
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
        scrollTo("Enable at least one tracking")
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
        // Everyday source controls are visible first; detailed frequency controls are
        // an explicitly labelled drill-down rather than a switch with unclear effects.
        scrollTo("Data collected")
        composeTestRule.onNodeWithText("Data collected", substring = true).assertIsDisplayed()
        scrollTo("Advanced controls")
        composeTestRule.onNodeWithText("Advanced controls", substring = true).assertIsDisplayed()
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

        scrollTo("Technical status")
        composeTestRule.onNodeWithTag("technicalStatusToggle").performClick()
        scrollTo("Requested and effective status")
        composeTestRule.onNodeWithTag("effectiveTrackingStatus").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tracking is not active", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
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

        scrollTo("Location")
        composeTestRule.onNodeWithTag("sourceFrequency-Location").performClick()
        composeTestRule.onNodeWithText("Responsive").performClick()
        selected shouldBe (TrackingSourceComponent.LOCATION to SourceCollectionFrequency.RESPONSIVE)
    }

    @Test
    fun advancedSourcesShowTheirActualRequestedCadence() {
        val responsive = SourceCollectionSettings(
            location = SourceCollectionFrequency.RESPONSIVE,
            activity = SourceCollectionFrequency.RESPONSIVE,
            steps = SourceCollectionFrequency.RESPONSIVE,
            pressure = SourceCollectionFrequency.RESPONSIVE,
            wifi = SourceCollectionFrequency.RESPONSIVE,
            cell = SourceCollectionFrequency.RESPONSIVE,
        )
        val plans = SemanticAcquisitionPlanFactory().create(
            settings = TrackingParamsState(sourceCollectionSettings = responsive),
            revision = 0L,
            createdAtMs = 0L,
            environment = SourcePlanEnvironment(LocationBackend.FUSED, true, emptySet()),
        ).plans
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        advancedSourceControlsEnabled = true,
                        sourceCollectionSettings = responsive,
                        sourcePlans = plans,
                    ),
                )
            }
        }

        listOf(
            "1 sec updates",
            "Continuous",
            "≤ 5 sec batching",
            "20 samples/sec",
            "Active scan",
            "Network changes",
        ).forEach { cadence ->
            scrollTo(cadence)
            composeTestRule.onNodeWithText(cadence, substring = true).assertIsDisplayed()
        }
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

        composeTestRule.onNodeWithTag("batteryEstimateCard").assertDoesNotExist()
        scrollTo("Technical status")
        composeTestRule.onNodeWithTag("technicalStatusToggle").performClick()
        scrollTo("Estimated current battery use")
        composeTestRule.onNodeWithTag("batteryEstimateCard").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confidence: Low").assertDoesNotExist()
        composeTestRule.onNodeWithText("How this estimate works").performClick()
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
								sourceTimerWakeupCount = 3L,
								sourceTimerRequestCount = 5L,
                        ),
                    ),
                )
            }
        }

        scrollTo("Technical status")
        composeTestRule.onNodeWithTag("technicalStatusToggle").performClick()
        scrollTo("Local runtime telemetry")
		composeTestRule.onNodeWithText("Projected events: 9", substring = true)
			.performScrollTo()
			.assertIsDisplayed()
		composeTestRule.onNodeWithText("source timer wakeups: 3", substring = true)
			.assertIsDisplayed()
    }

    @Test
    fun locationFiltersHiddenWhenLocationSourceIsDisabled() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState.copy(
                        advancedSourceControlsEnabled = true,
                        locationEnabled = false,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Location filters", substring = true).assertDoesNotExist()
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
        composeTestRule.onNodeWithText("Battery-efficient motion detection", substring = true)
            .assertDoesNotExist()
    }
}
