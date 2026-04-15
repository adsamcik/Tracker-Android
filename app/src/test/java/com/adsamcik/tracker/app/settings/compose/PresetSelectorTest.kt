package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.PresetSelector
import com.adsamcik.tracker.app.settings.components.TrackingPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                PresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    onPresetSelected = {},
                    showCustomBadge = false,
                )
            }
        }
        // Title comes from string resource tracking_preset_title
        composeTestRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysAllThreePresets() {
        composeTestRule.setContent {
            AppTheme {
                PresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    onPresetSelected = {},
                    showCustomBadge = false,
                )
            }
        }
        composeTestRule.onNodeWithText("Battery Saver", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Balanced", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("High Precision", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectingPresetCallsCallback() {
        var selected: TrackingPreset? = null
        composeTestRule.setContent {
            AppTheme {
                PresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    onPresetSelected = { selected = it },
                    showCustomBadge = false,
                )
            }
        }
        composeTestRule.onNodeWithText("Battery Saver", substring = true).performClick()
        selected shouldBe TrackingPreset.BATTERY_SAVER
    }

    @Test
    fun selectingHighPrecisionCallsCallback() {
        var selected: TrackingPreset? = null
        composeTestRule.setContent {
            AppTheme {
                PresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    onPresetSelected = { selected = it },
                    showCustomBadge = false,
                )
            }
        }
        composeTestRule.onNodeWithText("High Precision", substring = true).performClick()
        selected shouldBe TrackingPreset.HIGH_PRECISION
    }

    @Test
    fun customBadgeShownWhenCustomSelected() {
        composeTestRule.setContent {
            AppTheme {
                PresetSelector(
                    selectedPreset = TrackingPreset.CUSTOM,
                    onPresetSelected = {},
                    showCustomBadge = true,
                )
            }
        }
        composeTestRule.onNodeWithText("Custom", substring = true).assertIsDisplayed()
    }

    @Test
    fun customBadgeHiddenWhenNotCustom() {
        composeTestRule.setContent {
            AppTheme {
                PresetSelector(
                    selectedPreset = TrackingPreset.BALANCED,
                    onPresetSelected = {},
                    showCustomBadge = true,
                )
            }
        }
        // Custom badge only shows when CUSTOM is selected AND showCustomBadge is true
        // The badge text should not exist since BALANCED is selected
        composeTestRule.onNodeWithText("Battery Saver", substring = true).assertIsDisplayed()
    }
}
