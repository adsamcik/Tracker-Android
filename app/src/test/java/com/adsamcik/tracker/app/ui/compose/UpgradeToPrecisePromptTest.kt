package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.tracker.ui.UpgradeToPrecisePrompt
import com.adsamcik.tracker.app.tracker.ui.UpgradeReason
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeToPrecisePromptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysDialogTitle() {
        composeTestRule.setContent {
            AppTheme {
                UpgradeToPrecisePrompt(
                    onDismiss = {},
                    onUpgrade = {},
                )
            }
        }
        // Title comes from R.string.upgrade_to_precise_title = "Unlock Precise Tracking"
        composeTestRule.onNodeWithText("Unlock Precise Tracking", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun clickUpgrade_callsCallback() {
        var upgraded = false
        composeTestRule.setContent {
            AppTheme {
                UpgradeToPrecisePrompt(
                    onDismiss = {},
                    onUpgrade = { upgraded = true },
                )
            }
        }
        // Confirm button text from R.string.upgrade_to_precise_confirm -> "Enable Precise Location"
        composeTestRule.onNodeWithText("Enable Precise Location", substring = true).performClick()
        upgraded shouldBe true
    }

    @Test
    fun clickDismiss_callsCallback() {
        var dismissed = false
        composeTestRule.setContent {
            AppTheme {
                UpgradeToPrecisePrompt(
                    onDismiss = { dismissed = true },
                    onUpgrade = {},
                )
            }
        }
        // Dismiss button text from R.string.upgrade_to_precise_dismiss -> "Maybe Later"
        composeTestRule.onNodeWithText("Maybe Later", substring = true).performClick()
        dismissed shouldBe true
    }
}
