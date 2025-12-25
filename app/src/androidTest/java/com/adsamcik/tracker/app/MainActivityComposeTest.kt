package com.adsamcik.tracker.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MainActivityComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    private val context get() = composeRule.activity

    @Before
    fun ensureOnboardingCompleted() {
        markOnboardingCompletedForTests()
    }

    @Test
    fun map_expanded_by_default_has_expanded_state_description() {
        val expandedDescription = context.getString(R.string.main_nav_map_state_expanded)

        composeRule.onNodeWithTag("nav_map")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expandedDescription))

        composeRule.onNodeWithTag("nav_stats").assertIsNotSelected()
        composeRule.onNodeWithTag("nav_game").assertIsNotSelected()
    }

    @Test
    fun map_toggle_collapses_then_re_expands_and_updates_state_description() {
        val collapsedDescription = context.getString(R.string.main_nav_map_state_collapsed)
        val expandedDescription = context.getString(R.string.main_nav_map_state_expanded)

        composeRule.onNodeWithTag("nav_map").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_map")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsedDescription))
        composeRule.onNodeWithTag("nav_stats").assertIsSelected()

        composeRule.onNodeWithTag("nav_map").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_map")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expandedDescription))
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
