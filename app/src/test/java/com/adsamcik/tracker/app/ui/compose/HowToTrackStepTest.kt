package com.adsamcik.tracker.app.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.onboarding.data.AutoTrackingMode
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

    // Note: the CONTINUE CTA was moved out of HowToTrackStep into SetupScaffold's bottomBar
    // slot during the SYS-2 layout-overlay fix. HowToTrackStep is now content-only.

    @Test
    fun displaysAutoTrackingRadioCards() {
        composeTestRule.setContent {
            AppTheme {
                HowToTrackStep(
                    autoTrackingMode = AutoTrackingMode.OnFoot,
                    trackingPreset = TrackingPolicyPreset.BALANCED,
                    onAutoTrackingModeChange = {},
                    onPresetChange = {},
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeTestRule.onNodeWithTag("auto_tracking_disabled_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auto_tracking_on_foot_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auto_tracking_in_motion_card").assertIsDisplayed()
    }

    @Test
    fun autoTrackingModeChange_callsCallback() {
        var newMode: AutoTrackingMode? = null
        composeTestRule.setContent {
            AppTheme {
                HowToTrackStep(
                    autoTrackingMode = AutoTrackingMode.OnFoot,
                    trackingPreset = TrackingPolicyPreset.BALANCED,
                    onAutoTrackingModeChange = { newMode = it },
                    onPresetChange = {},
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeTestRule.onNodeWithTag("auto_tracking_disabled_card").performClick()
        newMode shouldBe AutoTrackingMode.Disabled
    }
}
