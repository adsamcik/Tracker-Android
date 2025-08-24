package com.adsamcik.tracker.app.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.activity.MainActivity
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed

private const val PREFS_NAME = "onboarding"
private const val PREF_COMPLETED = "completed"

private fun clearOnboardingPrefs() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit()
}

@RunWith(AndroidJUnit4::class)
class FreshLaunchShowsOnboardingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        clearOnboardingPrefs()
    }

    @Test
    fun onboarding_shows_on_fresh_launch() {
        // Wait until the onboarding welcome screen appears
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Get Started").fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("Welcome to Tracker").fetchSemanticsNodes().isNotEmpty()
        }

        // Assert the primary CTA exists and is visible
        composeRule.onNodeWithText("Get Started")
            .assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class OnboardingClickThroughTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<OnboardingActivity>()

    @Before
    fun setUp() {
        clearOnboardingPrefs()
    }

    @Test
    fun click_through_core_flow_reaches_success_and_completes() {
        // Welcome
        composeRule.onNodeWithText("Get Started").assertIsDisplayed().performClick()

        // Value demo
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("Ready to start tracking your life?").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Privacy
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("Privacy First").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // What to track
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("What do you want to track?").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Success screen
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Start Tracking!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start Tracking!").performClick()

        // Verify onboarding marked as completed
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(5_000) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_COMPLETED, false)
        }
        assertTrue(
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_COMPLETED, false)
        )
    }
}
