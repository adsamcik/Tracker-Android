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
import org.junit.BeforeClass

@RunWith(AndroidJUnit4::class)
class OnboardingTargetedFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<OnboardingActivity>()

    @Before
    fun setUp() {
        // Ensure previous runs don't mark onboarding as completed
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeAll() {
            // Inject fake permission manager so no system dialogs appear
            OnboardingPermissionManagerProvider.factory = { FakeOnboardingPermissionManager(it) }
        }
    }

    @Test
    fun welcome_to_value_demo_navigates_forward_and_back() {
        val primary = hasTestTag("onboarding_cta_primary")
        val back = hasTestTag("onboarding_cta_back")

        // Welcome -> Continue
        composeRule.onNode(primary).performClick()
        // ValueDemo -> Back
        composeRule.onNode(back).performClick()
        // Welcome -> Continue again
        composeRule.onNode(primary).performClick()
    }

    @Test
    @org.junit.Ignore("Covered by deterministic step-targeted test; previous version was timing-sensitive")
    fun progress_through_core_screens_reaches_done() {
        val primary = hasTestTag("onboarding_cta_primary")
        val skip = hasTestTag("onboarding_cta_skip")
        val done = hasTestTag("onboarding_cta_done")
        val doneText = hasText("Start Tracking!")
        val successRoot = hasTestTag("onboarding_success_root")
        val successTitle = hasText("Setup Complete!")

        fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean = try {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) { false }

        // Click through by tags only; permissions are faked, so no system UI interferes.
        repeat(10) {
            // If we're on the final screen, click Done and finish.
            if (exists(done) || exists(doneText) || exists(successRoot) || exists(successTitle)) {
                composeRule.runCatching { composeRule.onNode(done).performClick() }
                    .recoverCatching { composeRule.onNode(doneText).performClick() }
                    .getOrThrow()
                return
            }

            // Otherwise wait for a clickable CTA and proceed.
            composeRule.waitUntil(timeoutMillis = 10_000) { exists(primary) || exists(skip) || exists(done) }

            val clicked = try {
                composeRule.onNode(primary).performClick(); true
            } catch (_: AssertionError) {
                try {
                    composeRule.onNode(skip).performClick(); true
                } catch (_: AssertionError) { false }
            }
            if (!clicked) {
                // If nothing clickable, break with a clear failure
                throw AssertionError("No CTA found to click during onboarding progression")
            }
            composeRule.waitForIdle()
        }

        // Final assertion: we should be able to see the success indicators
        composeRule.waitUntil(timeoutMillis = 10_000) {
            exists(successRoot) || exists(done) || exists(doneText) || exists(successTitle)
        }
        composeRule.runCatching { composeRule.onNode(done).performClick() }
            .recoverCatching { composeRule.onNode(doneText).performClick() }
            .getOrThrow()
    }
}
