package com.adsamcik.tracker.app.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.app.onboarding.data.SetupUiState
import com.adsamcik.tracker.app.onboarding.ui.steps.WhatToCollectStep
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
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

    // Note: the START EXPLORING CTA was moved out of WhatToCollectStep into SetupScaffold's
    // bottomBar slot during the SYS-2 layout-overlay fix. WhatToCollectStep is now content-only.
    // Permission-result callbacks now carry per-permission Boolean tuples (e.g. fine+coarse+
    // background) and onPermissionStateHydrated is invoked on lifecycle resume per S3-F2 fix.

    @Test
    fun displaysSourcesContent() {
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
                    onLocationPermissionResult = { _, _, _ -> },
                    onBackgroundLocationResult = { _, _ -> },
                    onActivityPermissionResult = { _, _ -> },
                    onNotificationPermissionResult = {},
                    onPermissionStateHydrated = {},
                    contentPadding = PaddingValues(),
                )
            }
        }
        composeTestRule.onNodeWithText("Location").assertIsDisplayed()
    }
}
