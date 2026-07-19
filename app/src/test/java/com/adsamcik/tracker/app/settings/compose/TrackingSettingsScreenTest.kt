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
        skiDetectionEnabled = false,
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
        scrollTo("Ski detection")
        composeTestRule.onNodeWithText("Ski detection", substring = true).assertIsDisplayed()
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
    fun skiDetectionToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsContent(
                    uiState = defaultUiState,
                    onSkiDetectionChanged = { newValue = it },
                )
            }
        }
        scrollTo("Ski detection")
        composeTestRule.onNodeWithText("Ski detection", substring = true).performClick()
        newValue shouldBe true // Was false, toggling makes true
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
