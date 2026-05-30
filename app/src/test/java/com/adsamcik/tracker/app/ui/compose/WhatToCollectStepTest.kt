package com.adsamcik.tracker.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.components.LocationPrecisionMode
import com.adsamcik.tracker.app.onboarding.ui.steps.WhatToCollectStep
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
    fun permissionGrantButtonsExposeDistinctAccessibleLabels() {
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

        composeTestRule.onNodeWithContentDescription("Grant location permission")
            .assertExists()
        composeTestRule.onNodeWithContentDescription("Grant activity recognition permission")
            .assertExists()
    }

    @Test
    fun displaysSkipAndLocalOnlyReassurance() {
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

        composeTestRule.onNodeWithText("Start exploring without permissions", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Data stays on this device", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun activityPermissionRationaleScrollsClearOfPinnedStartExploringButton() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                AppTheme {
                    Box(modifier = Modifier.size(width = 412.dp, height = 760.dp)) {
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
            }
        }

        composeTestRule.onNodeWithTag("setup_perm_activity_clearance_anchor")
            .performScrollTo()

        val activityRationaleTextNode = composeTestRule.onNodeWithTag("setup_perm_activity_rationale_text")
            .fetchSemanticsNode()
        val activityGrantNode = composeTestRule.onNodeWithTag("setup_perm_activity_grant")
            .fetchSemanticsNode()
        val startExploringNode = composeTestRule.onNodeWithTag("setup_cta_complete")
            .fetchSemanticsNode()
        val minClearancePx = with(startExploringNode.layoutInfo.density) { 16.dp.toPx() }

        assertTrue(
            actual = activityRationaleTextNode.boundsInRoot.bottom <=
                startExploringNode.boundsInRoot.top - minClearancePx &&
                activityGrantNode.boundsInRoot.bottom <= startExploringNode.boundsInRoot.top - minClearancePx,
            message = "Expected activity permission rationale and Grant action to scroll clear of " +
                "the pinned Start Exploring CTA. rationaleBounds=${activityRationaleTextNode.boundsInRoot}, " +
                "grantBounds=${activityGrantNode.boundsInRoot}, ctaBounds=${startExploringNode.boundsInRoot}",
        )
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
        composeTestRule.onNodeWithText("Wi-Fi access points", substring = true)
            .assertExists()
        composeTestRule.onNodeWithText("cell tower IDs", substring = true)
            .assertExists()
    }
}
