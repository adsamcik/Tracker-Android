package com.adsamcik.tracker.app.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.ui.TrackingPresetSelector
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the tracking settings screen layout.
 * Since TrackingSettingsScreen uses hiltViewModel(), we recreate the layout
 * with injectable state to test UI rendering and interactions.
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

    @Composable
    private fun TrackingSettingsTestLayout(
        uiState: TrackingSettingsUiState = defaultUiState,
        onPresetSelected: (TrackingPreset) -> Unit = {},
        onTransitionDetectionChanged: (Boolean) -> Unit = {},
        onNotificationStyledChanged: (Boolean) -> Unit = {},
        onSkiDetectionChanged: (Boolean) -> Unit = {},
    ) {
        if (!uiState.isLoaded) return

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            // Validation warning
            if (!uiState.hasValidSources) {
                item {
                    Text(
                        text = "Warning: No valid sources",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Preset selector
            item {
                TrackingPresetSelector(
                    selectedPreset = uiState.currentPreset,
                    currentBatteryImpact = uiState.currentBatteryImpact,
                    onPresetSelected = onPresetSelected,
                )
            }

            // Transition detection toggle
            item {
                SwitchSettingsItem(
                    title = "Auto-tracking transitions",
                    checked = uiState.transitionDetectionEnabled,
                    onCheckedChange = onTransitionDetectionChanged,
                )
            }

            // Notification toggle
            item {
                SwitchSettingsItem(
                    title = "Styled notification",
                    checked = uiState.notificationStyled,
                    onCheckedChange = onNotificationStyledChanged,
                )
            }

            // Ski detection toggle
            item {
                SwitchSettingsItem(
                    title = "Ski detection",
                    checked = uiState.skiDetectionEnabled,
                    onCheckedChange = onSkiDetectionChanged,
                )
            }

            // Advanced section
            item {
                ExpandableSection(
                    title = "Advanced",
                    initiallyExpanded = false,
                ) {
                    SwitchSettingsItem(
                        title = "Location",
                        checked = uiState.locationEnabled,
                        onCheckedChange = {},
                        enabled = uiState.currentPreset == TrackingPreset.CUSTOM,
                    )
                    SwitchSettingsItem(
                        title = "Activity",
                        checked = uiState.activityEnabled,
                        onCheckedChange = {},
                        enabled = uiState.currentPreset == TrackingPreset.CUSTOM,
                    )
                }
            }
        }
    }

    @Test
    fun displaysPresetSelector() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Balanced", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysToggleSettings() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Auto-tracking transitions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Styled notification").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ski detection").assertIsDisplayed()
    }

    @Test
    fun transitionDetectionToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsTestLayout(
                    onTransitionDetectionChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Auto-tracking transitions").performClick()
        newValue shouldBe false // Was true, toggling makes false
    }

    @Test
    fun notificationToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsTestLayout(
                    onNotificationStyledChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Styled notification").performClick()
        newValue shouldBe false // Was true, toggling makes false
    }

    @Test
    fun skiDetectionToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsTestLayout(
                    onSkiDetectionChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Ski detection").performClick()
        newValue shouldBe true // Was false, toggling makes true
    }

    @Test
    fun validationWarningShownWhenNoValidSources() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsTestLayout(
                    uiState = defaultUiState.copy(hasValidSources = false),
                )
            }
        }
        composeTestRule.onNodeWithText("Warning", substring = true).assertIsDisplayed()
    }

    @Test
    fun validationWarningHiddenWhenSourcesValid() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsTestLayout(
                    uiState = defaultUiState.copy(hasValidSources = true),
                )
            }
        }
        composeTestRule.onNodeWithText("Warning", substring = true).assertDoesNotExist()
    }

    @Test
    fun advancedSectionCollapsedByDefault() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Advanced").assertIsDisplayed()
        // Content inside collapsed section should not be visible
        composeTestRule.onNodeWithText("Location").assertDoesNotExist()
    }

    @Test
    fun advancedSectionExpandsOnClick() {
        composeTestRule.setContent {
            AppTheme { TrackingSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Location").assertExists()
        composeTestRule.onNodeWithText("Activity").assertExists()
    }

    @Test
    fun notLoadedShowsNothing() {
        composeTestRule.setContent {
            AppTheme {
                TrackingSettingsTestLayout(
                    uiState = defaultUiState.copy(isLoaded = false),
                )
            }
        }
        composeTestRule.onNodeWithText("Balanced", substring = true).assertDoesNotExist()
    }
}
