package com.adsamcik.tracker.app.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.app.onboarding.permission.OnboardingPermissionManagerProvider
import com.adsamcik.tracker.app.onboarding.permission.FakeOnboardingPermissionManager
import com.adsamcik.tracker.app.onboarding.data.OnboardingStep
import org.junit.BeforeClass

@RunWith(AndroidJUnit4::class)
class OnboardingStepTargetedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<OnboardingActivity>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            OnboardingPermissionManagerProvider.factory = { FakeOnboardingPermissionManager(it) }
        }
    }

    @Test
    fun starting_from_auto_tracking_step_reaches_success_and_completes() {
        // Deep link into AutoTrackingSetup to minimize steps and avoid any optional branches.
        val scenarioIntent = OnboardingActivity.createIntentForStep(
            ApplicationProvider.getApplicationContext(),
            OnboardingStep.AutoTrackingSetup
        )
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(scenarioIntent)
        }

        val primary = hasTestTag("onboarding_cta_primary")
        val done = hasTestTag("onboarding_cta_done")
        val doneText = hasText("Start Tracking!")
        val successRoot = hasTestTag("onboarding_success_root")

        fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean = try {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) { false }

        // Click through AutoTrackingSetup -> Success
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(primary) }
        composeRule.onNode(primary).performClick()

        // Arrive at Success and finish
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(successRoot) || exists(done) || exists(doneText) }
        composeRule.runCatching { composeRule.onNode(done).performClick() }
            .recoverCatching { composeRule.onNode(doneText).performClick() }
            .getOrThrow()
    }

    @Test
    fun starting_from_what_to_track_reaches_success_and_completes() {
        // Deep link into WhatToTrack (defaults leave all toggles off).
        val scenarioIntent = OnboardingActivity.createIntentForStep(
            ApplicationProvider.getApplicationContext(),
            OnboardingStep.WhatToTrack
        )
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(scenarioIntent)
        }

        val primary = hasTestTag("onboarding_cta_primary")
        val done = hasTestTag("onboarding_cta_done")
        val doneText = hasText("Start Tracking!")
        val successRoot = hasTestTag("onboarding_success_root")

        fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean = try {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) { false }

        // WhatToTrack -> AutoTrackingSetup
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(primary) }
        composeRule.onNode(primary).performClick()
        // AutoTrackingSetup -> Success (since automatic tracking is off by default)
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(primary) || exists(successRoot) }
        if (exists(primary)) composeRule.onNode(primary).performClick()

        // Arrive and finish
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(successRoot) || exists(done) || exists(doneText) }
        composeRule.runCatching { composeRule.onNode(done).performClick() }
            .recoverCatching { composeRule.onNode(doneText).performClick() }
            .getOrThrow()
    }

    @Test
    fun starting_from_location_setup_reaches_success_and_completes() {
        // Deep link into LocationSetup (permissions are faked as granted).
        val scenarioIntent = OnboardingActivity.createIntentForStep(
            ApplicationProvider.getApplicationContext(),
            OnboardingStep.LocationSetup
        )
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.startActivity(scenarioIntent)
        }

        val primary = hasTestTag("onboarding_cta_primary")
        val done = hasTestTag("onboarding_cta_done")
        val doneText = hasText("Start Tracking!")
        val successRoot = hasTestTag("onboarding_success_root")

        fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean = try {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) { false }

        // LocationSetup -> Success (activity/wifi toggles are off by default)
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(primary) }
        composeRule.onNode(primary).performClick()

        // Arrive and finish
        composeRule.waitUntil(timeoutMillis = 10_000) { exists(successRoot) || exists(done) || exists(doneText) }
        composeRule.runCatching { composeRule.onNode(done).performClick() }
            .recoverCatching { composeRule.onNode(doneText).performClick() }
            .getOrThrow()
    }
}
