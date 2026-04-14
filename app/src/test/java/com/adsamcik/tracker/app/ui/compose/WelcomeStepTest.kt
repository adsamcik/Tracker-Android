package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.adsamcik.tracker.app.onboarding.ui.steps.WelcomeStep
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
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
}
