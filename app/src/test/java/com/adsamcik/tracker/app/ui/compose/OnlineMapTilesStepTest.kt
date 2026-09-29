package com.adsamcik.tracker.app.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.onboarding.data.SetupCompletionError
import com.adsamcik.tracker.app.onboarding.ui.steps.OnlineMapTilesStep
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineMapTilesStepTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun retryIsVisibleAndKeepsTheChosenMapMode() {
        var completed = false
        composeRule.setContent {
            AppTheme {
                OnlineMapTilesStep(
                    enabled = false,
                    onEnabledChange = {},
                    onComplete = { completed = true },
                    completionError = SetupCompletionError.RETENTION,
                )
            }
        }
        composeRule.onNodeWithTag("setup_online_tiles_off_choice").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_completion_error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithTag("setup_cta_online_tiles_complete").performClick()
        completed shouldBe true
    }

    @Test fun saveInProgressPreventsDuplicateCompletion() {
        var completions = 0
        composeRule.setContent {
            AppTheme {
                OnlineMapTilesStep(
                    enabled = true,
                    onEnabledChange = {},
                    onComplete = { completions++ },
                    completionInProgress = true,
                )
            }
        }
        composeRule.onNodeWithTag("setup_cta_online_tiles_complete").assertIsNotEnabled()
        composeRule.onNodeWithTag("setup_online_tiles_off_choice").assertIsNotEnabled()
        completions shouldBe 0
    }
}
