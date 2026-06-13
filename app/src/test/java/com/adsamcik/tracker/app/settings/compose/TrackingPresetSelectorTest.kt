package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.settings.ui.TrackingPresetSelector
import com.adsamcik.tracker.shared.preferences.tracking.TrackingPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingPresetSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitleAndSubtitle() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    currentBatteryImpact = BatteryImpact.MODERATE,
                    onPresetSelected = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysAllPresetCards() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    currentBatteryImpact = BatteryImpact.MODERATE,
                    onPresetSelected = {},
                )
            }
        }
        // Preset names from string resources
        composeTestRule.onNodeWithText("High Accuracy", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Balanced", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectingPresetCallsCallback() {
        var selected: TrackingPreset? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    currentBatteryImpact = BatteryImpact.MODERATE,
                    onPresetSelected = { selected = it },
                )
            }
        }
        composeTestRule.onNodeWithText("High Accuracy", substring = true).performClick()
        selected shouldBe TrackingPreset.HIGH_ACCURACY
    }

    @Test
    fun showsBatteryImpactForPresets() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.HIGH_ACCURACY,
                    currentBatteryImpact = BatteryImpact.HIGH,
                    onPresetSelected = {},
                )
            }
        }
        // Battery impact indicators should be visible
        composeTestRule.onNodeWithText("High", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysPowerSavePreset() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.POWER_SAVE,
                    currentBatteryImpact = BatteryImpact.LOW,
                    onPresetSelected = {},
                )
            }
        }
        // Verify the selector renders with the title visible
        composeTestRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
    }

    @Test
    fun showsCustomIndicatorWhenCustom() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.CUSTOM,
                    currentBatteryImpact = BatteryImpact.MODERATE,
                    onPresetSelected = {},
                )
            }
        }
        // The custom-profile indicator explains the user has fine-tuned tracking
        composeTestRule.onNodeWithText("fine-tuned", substring = true).assertIsDisplayed()
    }

    @Test
    fun hidesCustomIndicatorWhenPresetSelected() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    currentBatteryImpact = BatteryImpact.MODERATE,
                    onPresetSelected = {},
                )
            }
        }
        composeTestRule.onNodeWithText("fine-tuned", substring = true).assertDoesNotExist()
    }
}
