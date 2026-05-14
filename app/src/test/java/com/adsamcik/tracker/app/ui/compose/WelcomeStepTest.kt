package com.adsamcik.tracker.app.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.app.onboarding.ui.steps.WelcomeStep
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
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
    fun displaysWelcomeContent() {
        // Note: the CTA ("Get Started") was moved out of WelcomeStep into SetupScaffold's
        // bottomBar slot during the SYS-2 layout-overlay fix. WelcomeStep is now content-only.
        composeTestRule.setContent {
            AppTheme { WelcomeStep(contentPadding = PaddingValues()) }
        }
        composeTestRule.onNodeWithText("Track Your Journeys Privately").assertIsDisplayed()
    }
}
