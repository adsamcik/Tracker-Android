package com.adsamcik.tracker.app.onboarding

import android.content.Context
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.onboarding.permission.FakeOnboardingPermissionManager
import com.adsamcik.tracker.app.onboarding.permission.OnboardingPermissionManagerProvider
import com.adsamcik.tracker.app.onboarding.ui.OnboardingActivity
import com.adsamcik.tracker.testing.accessibility.assertMinTouchTargetSize
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive onboarding flow tests for streamlined single-screen onboarding:
 * - Welcome screen with "Get Started" CTA
 * - Location precision selection screen
 * - Completion navigates to main app
 * - Accessibility: touch targets on all CTA buttons
 */
@RunWith(AndroidJUnit4::class)
class OnboardingComprehensiveTest {

	@get:Rule
	val composeRule = createAndroidComposeRule<OnboardingActivity>()

	private val primary = hasTestTag("onboarding_cta_primary")
	private val back = hasTestTag("onboarding_cta_back")

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

	private fun exists(matcher: SemanticsMatcher): Boolean = try {
		composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
	} catch (_: IllegalStateException) { false }

	// region Happy Path

	@Test
	fun welcomeScreen_displaysCorrectly() {
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }
		composeRule.onNode(primary).assertIsDisplayed()
	}

	@Test
	fun happyPath_getStarted_navigatesToPrecisionSelector() {
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }
		composeRule.onNode(primary).performClick()
		composeRule.waitForIdle()
		
		// Should now be on precision selector with back button
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(back) }
		composeRule.onNode(back).assertIsDisplayed()
	}

	@Test
	fun backNavigation_returnsToPreviousScreen() {
		// Navigate to precision selector
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }
		composeRule.onNode(primary).performClick()
		composeRule.waitForIdle()

		// Wait for back button
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(back) }

		// Go back
		composeRule.onNode(back).performClick()
		composeRule.waitForIdle()

		// Should be back at welcome (primary visible)
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }
		composeRule.onNode(primary).assertIsDisplayed()
	}

	// endregion

	// region Accessibility

	@Test
	fun primaryCta_hasMinimumTouchTargetSize() {
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }
		composeRule.onNode(primary).assertMinTouchTargetSize()
	}

	@Test
	fun backCta_hasMinimumTouchTargetSize() {
		// Navigate forward to get back button
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }
		composeRule.onNode(primary).performClick()
		composeRule.waitForIdle()

		composeRule.waitUntil(timeoutMillis = 5_000) { exists(back) }
		composeRule.onNode(back).assertMinTouchTargetSize()
	}

	// endregion

	// region Error Recovery

	@Test
	fun onboarding_handlesRapidClicks_gracefully() {
		composeRule.waitUntil(timeoutMillis = 5_000) { exists(primary) }

		// Rapid clicks should not crash
		repeat(3) {
			if (exists(primary)) {
				composeRule.onNode(primary).performClick()
			}
		}
		composeRule.waitForIdle()

		// App should still be responsive
		composeRule.waitUntil(timeoutMillis = 5_000) {
			exists(primary) || exists(back)
		}
	}

	// endregion
}
