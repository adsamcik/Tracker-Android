package com.adsamcik.tracker.app

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
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
    private val runtimePermissions = mutableListOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

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
    .around(GrantPermissionRule.grant(*runtimePermissions))
    .around(composeRule)

    private val context get() = composeRule.activity

    @Before
    fun setup() {
        // The real application initializes storage asynchronously before rendering navigation.
        composeRule.waitUntil(timeoutMillis = APP_STARTUP_TIMEOUT_MILLIS) {
            hasNodeWithTag("nav_dashboard")
        }
    }

    @Test
    fun trackingFlow_startAndStopTracking() {
        // 1. Start on Tracker tab
        composeRule.onNodeWithTag("nav_dashboard").performClick()
        composeRule.waitForIdle()

        // 2. Start tracking from the current dashboard mode. A fresh profile uses the EMPTY
        // tile, while a profile with history uses the IDLE action ring (or its sticky pill).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            DASHBOARD_START_CONTROL_TAGS.any(::hasNodeWithTag)
        }
        val startControlTag = DASHBOARD_START_CONTROL_TAGS.first(::hasNodeWithTag)
        composeRule.onNodeWithTag(startControlTag)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        // The pill also exists while preparing. Its enabled End session action is published
        // only after the screen has entered the active recording state.
        val endSessionDescription = context.getString(
            com.adsamcik.tracker.tracker.R.string.shortcut_end_session
        )
        composeRule.waitUntil(timeoutMillis = 15_000) {
            hasEnabledVisibleControl(TRACKING_PILL_TAG, endSessionDescription)
        }
        val endSession = composeRule.onNodeWithTag(TRACKING_PILL_TAG)
            .assertContentDescriptionEquals(endSessionDescription)
            .assertIsDisplayed()
            .assertIsEnabled()

        // 4. Stop Tracking. Auto-tracking normally prompts for a stop option, but the flow also
        // supports configurations where the service stops immediately.
        val idleStartDescription = context.getString(
            com.adsamcik.tracker.dashboard.R.string.dashboard_cd_start_tracking
        )
        val trackingPillStartDescription = context.getString(
            com.adsamcik.tracker.dashboard.R.string.dashboard_pill_start
        )
        // (stop for N minutes / until charging / just stop) instead of stopping immediately —
        // pick "just stop" when that choice is shown.
        endSession.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            hasNodeWithTag(STOP_JUST_STOP_TAG) ||
                hasIdleStartControl(idleStartDescription, trackingPillStartDescription)
        }
        if (hasNodeWithTag(STOP_JUST_STOP_TAG)) {
            composeRule.onNodeWithTag(STOP_JUST_STOP_TAG).performClick()
        }

        // 5. Verify UI returns to "Idle" state.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            hasIdleStartControl(idleStartDescription, trackingPillStartDescription)
        }
    }

    private fun hasNodeWithTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasIdleStartControl(
        idleStartDescription: String,
        trackingPillStartDescription: String,
    ): Boolean =
        hasEnabledVisibleControl("dashboard_tile_track") ||
            hasEnabledVisibleControl(description = idleStartDescription) ||
            hasEnabledVisibleControl(TRACKING_PILL_TAG, trackingPillStartDescription)

    private fun hasEnabledVisibleControl(tag: String? = null, description: String? = null): Boolean {
        val matcher = when {
            tag != null && description != null -> hasTestTag(tag) and hasContentDescription(description)
            tag != null -> hasTestTag(tag)
            else -> hasContentDescription(requireNotNull(description))
        }
        return try {
            composeRule.onNode(matcher).assertIsDisplayed().assertIsEnabled()
            true
        } catch (_: AssertionError) {
            false
        }
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
        composeRule.onNodeWithTag("nav_dashboard").performClick()
        composeRule.waitForIdle()

        // 2. Click Settings button
        val settingsDesc = context.getString(
            com.adsamcik.tracker.dashboard.R.string.dashboard_cd_open_settings
        )
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
        composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
    }

    private companion object {
        const val APP_STARTUP_TIMEOUT_MILLIS = 30_000L
        val DASHBOARD_START_CONTROL_TAGS = listOf(
            "dashboard_tile_track",
            "tracking_action_ring",
            "tracking_pill",
        )
        const val TRACKING_PILL_TAG = "tracking_pill"
        const val STOP_JUST_STOP_TAG = "stop_tracking_option_just_stop"
    }
}
