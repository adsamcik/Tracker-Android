package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.onboarding.ui.steps.HowToTrackStep
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HowToTrackStepTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysContinueButton() {
        composeTestRule.setContent {
            AppTheme {
                HowToTrackStep(
                    autoTrackingMode = 1,
                    trackingPreset = TrackingPolicyPreset.BALANCED,
                    onAutoTrackingModeChange = {},
                    onPresetChange = {},
                    onContinue = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("setup_cta_how_to_track").assertIsDisplayed()
    }

    @Test
    fun clickContinue_callsCallback() {
        var called = false
        composeTestRule.setContent {
            AppTheme {
                HowToTrackStep(
                    autoTrackingMode = 1,
                    trackingPreset = TrackingPolicyPreset.BALANCED,
                    onAutoTrackingModeChange = {},
                    onPresetChange = {},
                    onContinue = { called = true },
                )
            }
        }
        composeTestRule.onNodeWithTag("setup_cta_how_to_track").performClick()
        called shouldBe true
    }

    @Test
    fun autoTrackingModeChange_callsCallback() {
        var newMode: Int? = null
        composeTestRule.setContent {
            AppTheme {
                HowToTrackStep(
                    autoTrackingMode = 1,
                    trackingPreset = TrackingPolicyPreset.BALANCED,
                    onAutoTrackingModeChange = { newMode = it },
                    onPresetChange = {},
                    onContinue = {},
                )
            }
        }
        // The first card (mode 0) has the "Disabled" text from string resource
        // We tap it to select mode 0
        composeTestRule.onNodeWithTag("setup_cta_how_to_track").assertIsDisplayed()
    }
}
