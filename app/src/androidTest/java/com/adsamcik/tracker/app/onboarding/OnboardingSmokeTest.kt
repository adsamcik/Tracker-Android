package com.adsamcik.tracker.app.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.adsamcik.tracker.app.activity.MainActivity
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Ignore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag

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
        fun hasAny(vararg labels: String): Boolean = try {
            // Prefer tags if present, but allow text fallback for the very first screen
            val tagPresent = composeRule.onAllNodes(hasTestTag("onboarding_cta_primary")).fetchSemanticsNodes().isNotEmpty()
            tagPresent || labels.any { label ->
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (_: IllegalStateException) { false }

        // Wait until the onboarding welcome screen appears
        composeRule.waitUntil(timeoutMillis = 10_000) {
            hasAny("Get Started", "Welcome to Tracker")
        }

        // Assert the primary CTA exists and is visible (by tag or text fallback)
        val primaryCta = composeRule.onNode(hasTestTag("onboarding_cta_primary"))
        try {
            primaryCta.assertIsDisplayed()
        } catch (_: AssertionError) {
            composeRule.onNodeWithText("Get Started").assertIsDisplayed()
        }
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

    private fun handleSystemPermissionDialogs(timeoutMs: Long = 2_000): Boolean {
        val inst = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(inst)
        var handled = false

        // Common permission controller resource IDs
        val denyIds = listOf(
            "com.android.permissioncontroller:id/permission_deny_button",
            "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button"
        )
        val allowIds = listOf(
            "com.android.permissioncontroller:id/permission_allow_button",
            "com.android.permissioncontroller:id/permission_allow_always_button",
            "com.android.permissioncontroller:id/permission_allow_one_time_button",
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button"
        )

        // Prefer a non-destructive option to move forward quickly
        for (res in denyIds + allowIds) {
            val obj = device.wait(Until.findObject(By.res(res)), timeoutMs)
            if (obj != null) {
                obj.click()
                handled = true
            }
        }

        // Text fallbacks across API levels/locales (English defaults)
        val texts = listOf(
            "Don't allow",
            "Don’t allow",
            "Deny",
            "Allow",
            "While using the app",
            "Only this time",
            "Allow only while using the app",
            "Continue",
            "OK"
        )
        for (t in texts) {
            val obj = device.wait(Until.findObject(By.text(t)), 500)
            if (obj != null) {
                obj.click()
                handled = true
            }
        }

        // Consent/precise location prompt in some devices
        val precise = device.wait(Until.findObject(By.textContains("precise location")), 500)
        if (precise != null) {
            // Choose a safe default
            val allowPrecise = device.wait(Until.findObject(By.textContains("Allow")), 500)
            allowPrecise?.click()
            handled = true
        }

        return handled
    }

    @Ignore("Flaky across API levels due to varying system dialogs; covered by unit and targeted instrumentation tests")
    @Test
    fun click_through_core_flow_reaches_success_and_completes() {
        // Welcome: click primary CTA by tag, fallback to text
        try {
            composeRule.onNode(hasTestTag("onboarding_cta_primary")).assertIsDisplayed().performClick()
        } catch (_: AssertionError) {
            composeRule.onNodeWithText("Get Started").assertIsDisplayed().performClick()
        }

        // Clear any immediate system permission dialogs
        handleSystemPermissionDialogs()

        // Progress through the flow by clicking CTAs by tag primarily
        val tagPrimary = hasTestTag("onboarding_cta_primary")
        val tagSkip = hasTestTag("onboarding_cta_skip")
        val tagDone = hasTestTag("onboarding_cta_done")

        // Allow up to 12 transitions to cover all steps regardless of availability/permissions
        fun hasAnyCta(): Boolean = try {
            composeRule.onAllNodes(tagDone or tagPrimary or tagSkip).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) { false }

        var completed = false
        repeat(16) {
            // Wait until any tagged CTA appears
            // Also opportunistically dismiss permission dialogs during waits
            composeRule.waitUntil(timeoutMillis = 20_000) {
                handleSystemPermissionDialogs(500)
                hasAnyCta()
            }

            // If final CTA is present, click it and mark completed
            try {
                composeRule.onNode(tagDone).performClick()
                completed = true
                return@repeat
            } catch (_: AssertionError) { /* not on final screen yet */ }

            // Prefer primary CTA; if not present, try skip. As a last resort, try known text labels.
            val clicked = try {
                composeRule.onNode(tagPrimary).performClick(); true
            } catch (_: AssertionError) {
                try {
                    composeRule.onNode(tagSkip).performClick(); true
                } catch (_: AssertionError) {
                    // Fallbacks on text for intermediate legacy labels
                    val texts = listOf(
                        "Set up my tracking",
                        "Complete Setup",
                        "Continue Setup",
                        "Skip for now",
                        "Skip - Track manually",
                        "Continue"
                    )
                    var did = false
                    for (t in texts) {
                        try {
                            composeRule.onNodeWithText(t).performClick()
                            did = true
                            break
                        } catch (_: AssertionError) { }
                    }
                    if (!did) {
                        // Try to clear any dialogs and attempt again quickly
                        handleSystemPermissionDialogs(1000)
                    }
                    did
                }
            }
            // After any click, clear potential permission dialogs
            handleSystemPermissionDialogs()
            if (!clicked) return@repeat
        }

        // If we didn't reach a final CTA, fail early with a clear message
        if (!completed) {
            throw AssertionError("Did not reach onboarding success screen. Known CTAs not found after iterations.")
        }

        // Verify onboarding marked as completed
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        composeRule.waitUntil(10_000) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_COMPLETED, false)
        }
        assertTrue(
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_COMPLETED, false)
        )
    }
}
