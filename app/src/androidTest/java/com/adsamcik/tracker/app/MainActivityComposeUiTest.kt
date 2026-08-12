package com.adsamcik.tracker.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityComposeUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    private val context get() = composeRule.activity

    @Before
    fun ensureOnboardingCompleted() {
        markOnboardingCompletedForTests()
    }

    @Test
    fun smoke_launches_tracker_selected_by_default() {
        composeRule.onNodeWithTag("nav_dashboard")
            .assertIsSelected()
    }

    @Test
    fun bottom_nav_navigates_between_tabs_and_back_goes_to_tracker() {
        composeRule.onNodeWithTag("nav_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_stats").assertIsSelected()
        composeRule.onNodeWithTag("nav_dashboard")
            .assertIsNotSelected()

        composeRule.onNodeWithTag("nav_game").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_game").assertIsSelected()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
    }

    // Note: Intent-based tests moved to MainActivityIntentTest.kt using ActivityScenario

    companion object {
        @JvmStatic
        @BeforeClass
        fun markOnboardingCompleted() {
            markOnboardingCompletedForTests()
        }
    }
}
