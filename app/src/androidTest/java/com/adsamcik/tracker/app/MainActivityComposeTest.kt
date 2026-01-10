package com.adsamcik.tracker.app

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Tests for MainActivityCompose navigation behavior.
 * Verifies tab selection and navigation state.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    @Before
    fun ensureOnboardingCompleted() {
        markOnboardingCompletedForTests()
    }

    @Test
    fun tracker_selected_by_default() {
        composeRule.waitForIdle()
        
        // Tracker is the home screen and should be selected by default
        composeRule.onNodeWithTag("nav_tracker").assertIsSelected()
        composeRule.onNodeWithTag("nav_stats").assertIsNotSelected()
        composeRule.onNodeWithTag("nav_map").assertIsNotSelected()
        composeRule.onNodeWithTag("nav_game").assertIsNotSelected()
    }

    @Test
    fun navigate_to_map_then_back() {
        composeRule.waitForIdle()
        
        // Navigate to map
        composeRule.onNodeWithTag("nav_map").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_map").assertIsSelected()
        composeRule.onNodeWithTag("nav_tracker").assertIsNotSelected()
        
        // Navigate back to tracker
        composeRule.onNodeWithTag("nav_tracker").performClick()
        composeRule.waitForIdle()
        
        composeRule.onNodeWithTag("nav_tracker").assertIsSelected()
        composeRule.onNodeWithTag("nav_map").assertIsNotSelected()
    }

    @Test
    fun navigate_to_stats_and_game() {
        composeRule.waitForIdle()
        
        // Navigate to stats
        composeRule.onNodeWithTag("nav_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_stats").assertIsSelected()
        
        // Navigate to game
        composeRule.onNodeWithTag("nav_game").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_game").assertIsSelected()
        composeRule.onNodeWithTag("nav_stats").assertIsNotSelected()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun markOnboardingCompleted() {
            markOnboardingCompletedForTests()
        }
    }
}
