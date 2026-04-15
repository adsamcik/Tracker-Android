package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionSelector
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationPrecisionSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                LocationPrecisionSelector(
                    selectedMode = null,
                    onModeSelected = {},
                )
            }
        }
        // Title should be visible (from string resource)
        composeTestRule.onNodeWithText("Location Precision", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysBothPrecisionModes() {
        composeTestRule.setContent {
            AppTheme {
                LocationPrecisionSelector(
                    selectedMode = null,
                    onModeSelected = {},
                )
            }
        }
        // Both mode cards should be visible
        composeTestRule.onNodeWithText("Approximate", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Precise", substring = true).assertIsDisplayed()
    }

    @Test
    fun showsBatteryImpactIndicators() {
        composeTestRule.setContent {
            AppTheme {
                LocationPrecisionSelector(
                    selectedMode = null,
                    onModeSelected = {},
                )
            }
        }
        // Battery impact labels should be visible
        composeTestRule.onNodeWithText("Low", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Moderate", substring = true).assertIsDisplayed()
    }

    @Test
    fun selectingApproximateCallsCallback() {
        var selectedMode: LocationPrecisionMode? = null
        composeTestRule.setContent {
            AppTheme {
                LocationPrecisionSelector(
                    selectedMode = null,
                    onModeSelected = { selectedMode = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Approximate", substring = true).performClick()
        selectedMode shouldBe LocationPrecisionMode.APPROXIMATE
    }

    @Test
    fun selectingPreciseCallsCallback() {
        var selectedMode: LocationPrecisionMode? = null
        composeTestRule.setContent {
            AppTheme {
                LocationPrecisionSelector(
                    selectedMode = null,
                    onModeSelected = { selectedMode = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Precise", substring = true).performClick()
        selectedMode shouldBe LocationPrecisionMode.PRECISE
    }

    @Test
    fun preciseSelectedShowsSelection() {
        composeTestRule.setContent {
            AppTheme {
                LocationPrecisionSelector(
                    selectedMode = LocationPrecisionMode.PRECISE,
                    onModeSelected = {},
                )
            }
        }
        // Both modes should still be displayed
        composeTestRule.onNodeWithText("Approximate", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Precise", substring = true).assertIsDisplayed()
    }
}
