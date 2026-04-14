package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItemWithHelp
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsItemWithHelpTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun switchWithHelp_displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                SwitchSettingsItemWithHelp(
                    title = "WiFi scanning",
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("WiFi scanning").assertIsDisplayed()
    }

    @Test
    fun switchWithHelp_toggleCallsCallback() {
        var newVal: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                SwitchSettingsItemWithHelp(
                    title = "WiFi scanning",
                    checked = true,
                    onCheckedChange = { newVal = it },
                )
            }
        }
        composeTestRule.onNodeWithText("WiFi scanning").performClick()
        newVal shouldBe false
    }

    @Test
    fun switchWithHelp_helpIconOpensDialog() {
        composeTestRule.setContent {
            AppTheme {
                SwitchSettingsItemWithHelp(
                    title = "WiFi scanning",
                    checked = true,
                    onCheckedChange = {},
                    helpTextRes = com.adsamcik.tracker.R.string.action_help,
                )
            }
        }
        // Help button has contentDescription "Help"
        composeTestRule.onNodeWithContentDescription("Help").performClick()
        // Dialog should be open with the title
        composeTestRule.onNodeWithText("Got it").assertIsDisplayed()
    }

    @Test
    fun sliderWithHelp_displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                SliderSettingsItemWithHelp(
                    title = "Min distance",
                    value = 10f,
                    valueRange = 0f..100f,
                    steps = 9,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Min distance").assertIsDisplayed()
        composeTestRule.onNodeWithText("10 m").assertIsDisplayed()
    }
}
