package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.ui.steps.WelcomeStep
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.testing.accessibility.assertIsAccessibilityHeading
import com.adsamcik.tracker.testing.accessibility.assertMinTouchTargetSize
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WelcomeStepTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysGetStartedButton() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }
        composeTestRule.onNodeWithTag("setup_cta_get_started")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun welcomeTitle_isAccessibilityHeading() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        composeTestRule.onNodeWithText("Track Your Journeys Privately")
            .assertIsAccessibilityHeading()
    }

    @Test
    fun displaysPrivacySubtitleWithoutAmbiguousTrackingCopy() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        composeTestRule.onNodeWithText(
            "All data stays on your device. No cloud sync or third-party tracking.",
        ).assertIsDisplayed()
    }

    @Test
    fun primaryActionsMeetMinimumTouchTargets() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        composeTestRule.onNodeWithTag("setup_privacy_policy_button")
            .performScrollTo()
            .assertMinTouchTargetSize()

        composeTestRule.onNodeWithTag("setup_cta_get_started")
            .performScrollTo()
            .assertMinTouchTargetSize()
    }

    @Test
    fun getStartedLabel_isCenteredWithinPrimaryButton() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        val buttonNode = composeTestRule.onNodeWithTag(
            testTag = "setup_cta_get_started",
            useUnmergedTree = true,
        )
            .performScrollTo()
            .fetchSemanticsNode()
        val labelNode = composeTestRule.onNodeWithText(
            text = "GET STARTED",
            useUnmergedTree = true,
        ).fetchSemanticsNode()

        val centerDelta = abs(buttonNode.boundsInRoot.center.x - labelNode.boundsInRoot.center.x)
        val tolerancePx = with(buttonNode.layoutInfo.density) { 1.dp.toPx() }
        assertTrue(
            actual = centerDelta <= tolerancePx,
            message = "Expected GET STARTED label to be horizontally centered in the primary button. " +
                "buttonBounds=${buttonNode.boundsInRoot}, labelBounds=${labelNode.boundsInRoot}",
        )
    }

    @Test
    fun clickGetStarted_callsCallback() {
        var called = false
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = { called = true }) }
        }
        composeTestRule.onNodeWithTag("setup_cta_get_started")
            .performScrollTo()
            .performClick()
        called shouldBe true
    }

    @Test
    fun displaysPrivacyPolicyEntryPoint() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        composeTestRule.onNodeWithTag("setup_privacy_policy_button")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun privacyPolicyEntryPoint_hasSingleMergedClickTarget() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        composeTestRule.onNodeWithTag("setup_privacy_policy_button")
            .performScrollTo()
            .assertHasClickAction()

        composeTestRule.onAllNodes(hasClickAction() and hasText("Read Privacy Policy"))
            .fetchSemanticsNodes().size shouldBe 1
    }

    @Test
    fun clickPrivacyPolicyEntryPoint_opensBundledPolicyDialog() {
        composeTestRule.setContent {
            AppTheme { WelcomeStep(onGetStarted = {}) }
        }

        composeTestRule.onNodeWithTag("setup_privacy_policy_button")
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText("Privacy Policy", substring = false)
            .assertIsDisplayed()
    }

    @Test
    fun displaysSafeSetupMessageWhenOnboardingReadFails() {
        composeTestRule.setContent {
            AppTheme {
                WelcomeStep(
                    onGetStarted = {},
                    showOnboardingReadError = true,
                )
            }
        }

        composeTestRule.onNodeWithTag("setup_onboarding_read_error")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
