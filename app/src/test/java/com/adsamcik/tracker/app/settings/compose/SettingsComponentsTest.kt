package com.adsamcik.tracker.app.settings.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SettingsItemWithValue
import com.adsamcik.tracker.app.settings.components.SettingsNoticeCard
import com.adsamcik.tracker.app.settings.components.SettingsSummaryItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItem
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsGroupCard
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.testing.accessibility.assertIsAccessibilityHeading
import com.adsamcik.tracker.testing.accessibility.assertMinTouchTargetSize
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // region SettingsItem

    @Test
    fun settingsItem_displaysTitle() {
        composeTestRule.setContent {
            AppTheme { SettingsItem(title = "General", onClick = {}) }
        }
        composeTestRule.onNodeWithText("General").assertIsDisplayed()
    }

    @Test
    fun settingsItem_displaysSubtitle() {
        composeTestRule.setContent {
            AppTheme {
                SettingsItem(title = "Theme", subtitle = "Dark mode", onClick = {})
            }
        }
        composeTestRule.onNodeWithText("Dark mode").assertIsDisplayed()
    }

    @Test
    fun settingsItem_exposesMergedDescriptionAndTouchTarget() {
        composeTestRule.setContent {
            AppTheme {
                SettingsItem(title = "Theme", subtitle = "Dark mode", onClick = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Theme: Dark mode")
            .assertMinTouchTargetSize()
    }

    @Test
    fun settingsItem_clickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { SettingsItem(title = "Click me", onClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Click me").performClick()
        clicked shouldBe true
    }

    // endregion

    // region SettingsItemWithValue

    @Test
    fun settingsItemWithValue_displaysValue() {
        composeTestRule.setContent {
            AppTheme {
                SettingsItemWithValue(title = "Unit", value = "Metric", onClick = {})
            }
        }
        composeTestRule.onNodeWithText("Metric").assertIsDisplayed()
    }

    @Test
    fun settingsItemWithValue_clickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme {
                SettingsItemWithValue(
                    title = "Unit",
                    value = "Metric",
                    onClick = { clicked = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Unit").performClick()
        clicked shouldBe true
    }

    @Test
    fun settingsItemWithValue_exposesSelectionAndTouchTarget() {
        composeTestRule.setContent {
            AppTheme {
                SettingsItemWithValue(title = "Unit", value = "Metric", onClick = {})
            }
        }

        composeTestRule.onNodeWithContentDescription("Unit: Metric")
            .assertMinTouchTargetSize()
    }

    // endregion

    // region SwitchSettingsItem

    @Test
    fun switchSettingsItem_displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                SwitchSettingsItem(
                    title = "Auto-tracking",
                    checked = false,
                    onCheckedChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Auto-tracking").assertIsDisplayed()
    }

    @Test
    fun switchSettingsItem_toggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                SwitchSettingsItem(
                    title = "Toggle me",
                    checked = false,
                    onCheckedChange = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Toggle me").performClick()
        newValue shouldBe true
    }

    @Test
    fun switchSettingsItem_disabledDoesNotToggle() {
        var toggled = false
        composeTestRule.setContent {
            AppTheme {
                SwitchSettingsItem(
                    title = "Disabled",
                    checked = false,
                    onCheckedChange = { toggled = true },
                    enabled = false,
                )
            }
        }
        composeTestRule.onNodeWithText("Disabled").performClick()
        toggled shouldBe false
    }

    // endregion

    // region SliderSettingsItem

    @Test
    fun sliderSettingsItem_displaysLabel() {
        composeTestRule.setContent {
            AppTheme {
                SliderSettingsItem(
                    title = "Distance",
                    value = 50f,
                    valueRange = 0f..100f,
                    steps = 9,
                    valueLabel = { "${it.toInt()} m" },
                    onValueChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Distance").assertIsDisplayed()
        composeTestRule.onNodeWithText("50 m").assertIsDisplayed()
    }

    // endregion

    // region SectionHeader

    @Test
    fun sectionHeader_displaysText() {
        composeTestRule.setContent {
            AppTheme { SectionHeader(text = "Advanced") }
        }
        composeTestRule.onNodeWithText("Advanced").assertIsDisplayed()
    }

    @Test
    fun sectionHeader_isAccessibilityHeading() {
        composeTestRule.setContent {
            AppTheme { SectionHeader(text = "Advanced") }
        }

        composeTestRule.onNodeWithText("Advanced")
            .assertIsAccessibilityHeading()
    }

    // endregion

    // region SettingsGroupCard

    @Test
    fun settingsGroupCard_displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                SettingsGroupCard(title = "Group Title") {
                    // empty
                }
            }
        }
        composeTestRule.onNodeWithText("Group Title").assertIsDisplayed()
    }

    @Test
    fun settingsGroupCardTitle_isAccessibilityHeading() {
        composeTestRule.setContent {
            AppTheme {
                SettingsGroupCard(title = "Group Title") {
                    // empty
                }
            }
        }

        composeTestRule.onNodeWithText("Group Title")
            .assertIsAccessibilityHeading()
    }

    @Test
    fun settingsGroupCard_displaysContent() {
        composeTestRule.setContent {
            AppTheme {
                SettingsGroupCard(title = "Group") {
                    SettingsItem(title = "Child item", onClick = {})
                }
            }
        }
        composeTestRule.onNodeWithText("Child item").assertIsDisplayed()
    }

    @Test
    fun settingsSummaryItem_exposesStaticLabelAndValue() {
        composeTestRule.setContent {
            AppTheme {
                SettingsSummaryItem(title = "Version", value = "10.0")
            }
        }

        composeTestRule.onNodeWithContentDescription("Version: 10.0")
            .assertIsDisplayed()
    }

    @Test
    fun settingsNoticeCard_displaysTitleAndMessage() {
        composeTestRule.setContent {
            AppTheme {
                SettingsNoticeCard(title = "Privacy", text = "Data stays on this device")
            }
        }

        composeTestRule.onNodeWithText("Privacy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Data stays on this device").assertIsDisplayed()
    }

    // endregion
}
