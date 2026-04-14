package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatteryImpactIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysLowImpactLabel() {
        composeTestRule.setContent {
            AppTheme { BatteryImpactIndicator(impact = BatteryImpact.LOW) }
        }
        // The label comes from string resource R.string.battery_impact_low
        composeTestRule.onNodeWithText("Low", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysModerateImpactLabel() {
        composeTestRule.setContent {
            AppTheme { BatteryImpactIndicator(impact = BatteryImpact.MODERATE) }
        }
        composeTestRule.onNodeWithText("Moderate", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysHighImpactLabel() {
        composeTestRule.setContent {
            AppTheme { BatteryImpactIndicator(impact = BatteryImpact.HIGH) }
        }
        composeTestRule.onNodeWithText("High", substring = true).assertIsDisplayed()
    }

    @Test
    fun compactMode_hidesLabel_whenRequested() {
        composeTestRule.setContent {
            AppTheme {
                BatteryImpactIndicator(
                    impact = BatteryImpact.LOW,
                    showLabel = false,
                )
            }
        }
        // Label text should not be rendered
        composeTestRule.onNodeWithText("Low", substring = true).assertDoesNotExist()
    }
}
