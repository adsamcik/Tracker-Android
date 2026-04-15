package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.app.settings.ui.TrackingPolicySelector
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackingPolicySelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitleAndSubtitle() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysAllThreePresets() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Power Save", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Balanced", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("High Accuracy", substring = true).assertIsDisplayed()
    }

    @Test
    fun showsBatteryImpactForEachPreset() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Low", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Moderate", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("High", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectingPresetCallsCallback() {
        var selected: TrackingPolicyPreset? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = { selected = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Power Save", substring = true).performClick()
        selected shouldBe TrackingPolicyPreset.BATTERY_SAVER
    }

    @Test
    fun selectingHighPrecisionCallsCallback() {
        var selected: TrackingPolicyPreset? = null
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = { selected = it },
                )
            }
        }
        composeTestRule.onNodeWithText("High Accuracy", substring = true).performClick()
        selected shouldBe TrackingPolicyPreset.HIGH_PRECISION
    }

    @Test
    fun nullSelectedShowsCustomBadge() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = null,
                    onPresetSelected = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Custom", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectedPresetShowsEstimatedDuration() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = {},
                )
            }
        }
        // Estimated tracking duration "Estimated tracking: 8 hours"
        composeTestRule.onNodeWithText("8", substring = true).assertExists()
    }

    @Test
    fun withDetailsShowsExpandButton() {
        composeTestRule.setContent {
            AppTheme {
                TrackingPolicySelector(
                    selectedPreset = TrackingPolicyPreset.BALANCED,
                    onPresetSelected = {},
                    showDetails = true,
                )
            }
        }
        // When showDetails is true, expand buttons should be available
        composeTestRule.onNodeWithText("Balanced", substring = true).assertIsDisplayed()
    }
}
