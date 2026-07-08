package com.adsamcik.tracker.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class StandardFlowsTest {

    private val composeRule = createAndroidComposeRule<MainActivityCompose>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(object : TestRule {
        override fun apply(base: Statement, description: Description): Statement {
            return object : Statement() {
                override fun evaluate() {
                    markOnboardingCompletedForTests()
                    base.evaluate()
                }
            }
        }
    })
    .around(GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    ))
    .around(composeRule)

    private val context get() = composeRule.activity

    @Before
    fun setup() {
        // Onboarding is handled by the RuleChain
    }

    @Test
    fun trackingFlow_startAndStopTracking() {
        // 1. Start on Tracker tab
        composeRule.onNodeWithTag("nav_tracker").performClick()
        composeRule.waitForIdle()

        // 2. Verify Start Tracking button is visible and enabled
        composeRule.onNodeWithTag("tracking_fab")
            .assertIsDisplayed()
            .assertIsEnabled()

        // 3. Start Tracking
        composeRule.onNodeWithTag("tracking_fab").performClick()
        composeRule.waitForIdle()


        // 4. Verify UI changes to "Tracking" state
        // The FAB icon changes to Stop.
        val stopDescription = context.getString(com.adsamcik.tracker.tracker.R.string.description_tracking_stop)
        composeRule.onNodeWithContentDescription(stopDescription).assertIsDisplayed()

        // 5. Stop Tracking. Auto-tracking is on by default, so stopping now prompts a choice
        // (stop for N minutes / until charging / just stop) instead of stopping immediately —
        // pick "just stop" to preserve this test's original intent.
        composeRule.onNodeWithTag("tracking_fab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("stop_tracking_option_just_stop").performClick()
        composeRule.waitForIdle()

        // 6. Verify UI returns to "Idle" state
        val startDescription = context.getString(com.adsamcik.tracker.tracker.R.string.description_tracking_start)
        composeRule.onNodeWithContentDescription(startDescription).assertIsDisplayed()
    }

    @Test
    fun mapFlow_navigationAndInteraction() {
        // 1. Navigate to Map tab
        composeRule.onNodeWithTag("nav_map").performClick()
        composeRule.waitForIdle()

        // 2. Verify Map Sheet controls are displayed
        // "My location" button in MapSheet
        composeRule.onNodeWithContentDescription("My location").assertIsDisplayed()

        // 3. Toggle Map Sheet (Collapse/Expand)
        composeRule.onNodeWithTag("nav_map").performClick() // Collapse
        composeRule.waitForIdle()
        
        composeRule.onNodeWithTag("nav_map").performClick() // Expand
        composeRule.waitForIdle()
        
        composeRule.onNodeWithContentDescription("My location").assertIsDisplayed()
    }

    @Test
    fun statisticsFlow_navigationAndContent() {
        // 1. Navigate to Stats tab
        composeRule.onNodeWithTag("nav_stats").performClick()
        composeRule.waitForIdle()

        // 2. Verify Stats screen content
        composeRule.onNodeWithTag("nav_stats").assertIsSelected()
        
        // We can't guarantee content state (empty vs populated) without seeding,
        // so we verify we are on the correct tab and no crash occurred.
    }

    @Test
    fun settingsFlow_accessFromTracker() {
        // 1. Navigate to Tracker tab
        composeRule.onNodeWithTag("nav_tracker").performClick()
        composeRule.waitForIdle()

        // 2. Click Settings button
        val settingsDesc = context.getString(com.adsamcik.tracker.tracker.R.string.description_settings)
        composeRule.onNodeWithContentDescription(settingsDesc).performClick()
        composeRule.waitForIdle()

        // 3. Verify Settings screen is displayed
        val settingsTitle = context.getString(R.string.settings_title)
        composeRule.onNodeWithText(settingsTitle).assertIsDisplayed()
        
        // 4. Navigate back
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        // 5. Verify Tracker screen is displayed
        composeRule.onNodeWithTag("nav_tracker").assertIsSelected()
    }
}
