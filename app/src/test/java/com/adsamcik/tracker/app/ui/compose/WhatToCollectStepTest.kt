package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.onboarding.ui.steps.WhatToCollectStep
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WhatToCollectStepTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysCompleteButton() {
        composeTestRule.setContent {
            AppTheme {
                WhatToCollectStep(
                    state = SetupUiState(),
                    onLocationEnabledChange = {},
                    onLocationPrecisionChange = {},
                    onActivityEnabledChange = {},
                    onStepsEnabledChange = {},
                    onWifiEnabledChange = {},
                    onCellEnabledChange = {},
                    onLocationPermissionResult = {},
                    onBackgroundLocationResult = {},
                    onActivityPermissionResult = {},
                    onNotificationPermissionResult = {},
                    onWifiPermissionResult = {},
                    onCellPermissionResult = {},
                    onComplete = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("setup_cta_complete").assertIsDisplayed()
    }

    @Test
    fun clickComplete_callsCallback() {
        var called = false
        composeTestRule.setContent {
            AppTheme {
                WhatToCollectStep(
                    state = SetupUiState(),
                    onLocationEnabledChange = {},
                    onLocationPrecisionChange = {},
                    onActivityEnabledChange = {},
                    onStepsEnabledChange = {},
                    onWifiEnabledChange = {},
                    onCellEnabledChange = {},
                    onLocationPermissionResult = {},
                    onBackgroundLocationResult = {},
                    onActivityPermissionResult = {},
                    onNotificationPermissionResult = {},
                    onWifiPermissionResult = {},
                    onCellPermissionResult = {},
                    onComplete = { called = true },
                )
            }
        }
        composeTestRule.onNodeWithTag("setup_cta_complete").performClick()
        called shouldBe true
    }

    @Test
    fun wifiAndCellTogglesShowPrivacyRationalesWhenEnabledWithoutPermissions() {
        composeTestRule.setContent {
            AppTheme {
                WhatToCollectStep(
                    state = SetupUiState(wifiEnabled = true, cellEnabled = true),
                    onLocationEnabledChange = {},
                    onLocationPrecisionChange = {},
                    onActivityEnabledChange = {},
                    onStepsEnabledChange = {},
                    onWifiEnabledChange = {},
                    onCellEnabledChange = {},
                    onLocationPermissionResult = {},
                    onBackgroundLocationResult = {},
                    onActivityPermissionResult = {},
                    onNotificationPermissionResult = {},
                    onWifiPermissionResult = {},
                    onCellPermissionResult = {},
                    onComplete = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Wi-Fi scans can reveal nearby networks", substring = true)
            .assertExists()
        composeTestRule.onNodeWithText("Cell tower data can be combined with your route", substring = true)
            .assertExists()
    }
}
