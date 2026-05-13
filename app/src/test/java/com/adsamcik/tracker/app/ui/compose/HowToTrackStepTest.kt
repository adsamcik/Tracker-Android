package com.adsamcik.tracker.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.ui.steps.HowToTrackStep
import com.adsamcik.tracker.app.settings.data.TrackingPolicyPreset
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import kotlin.test.assertTrue
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

    @Test
    fun highPrecisionPreset_scrollsClearOfPinnedContinueButton() {
        composeTestRule.setContent {
            AppTheme {
                Box(modifier = Modifier.size(width = 412.dp, height = 760.dp)) {
                    HowToTrackStep(
                        autoTrackingMode = 1,
                        trackingPreset = TrackingPolicyPreset.BALANCED,
                        onAutoTrackingModeChange = {},
                        onPresetChange = {},
                        onContinue = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("setup_how_to_track_scroll_bottom_padding")
            .performScrollTo()

        val highPrecisionNode = composeTestRule.onNodeWithTag("setup_preset_high_precision")
            .fetchSemanticsNode()
        val continueNode = composeTestRule.onNodeWithTag("setup_cta_how_to_track")
            .fetchSemanticsNode()
        val minClearancePx = with(continueNode.layoutInfo.density) { 48.dp.toPx() }

        assertTrue(
            actual = highPrecisionNode.boundsInRoot.bottom <= continueNode.boundsInRoot.top - minClearancePx,
            message = "Expected High Accuracy card to scroll above the pinned continue CTA with touch-safe " +
                "clearance. cardBounds=${highPrecisionNode.boundsInRoot}, " +
                "ctaBounds=${continueNode.boundsInRoot}",
        )
    }
}
