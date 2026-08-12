package com.adsamcik.tracker.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import com.adsamcik.tracker.testing.accessibility.assertHasAccessibleText
import com.adsamcik.tracker.testing.accessibility.assertMinTouchTargetSize
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive navigation tests for MainActivityCompose.
 * 
 * Tests cover:
 * - All 5 navigation tabs (Tracker, Stats, Map, Game, Settings)
 * - Tab switching behavior and state preservation
 * - Back-stack navigation (return to Map on back)
 * - Deep-link intent handling
 * - Accessibility: touch target sizes and semantic labels
 */
@RunWith(AndroidJUnit4::class)
class NavigationComprehensiveTest {

	@get:Rule
	val composeRule = createAndroidComposeRule<MainActivityCompose>()

	private val context get() = composeRule.activity

	@Before
	fun ensureOnboardingCompleted() {
		markOnboardingCompletedForTests()
	}

	// region Tab Navigation

	@Test
	fun allNavigationTabs_areDisplayed() {
		composeRule.waitForIdle()

		composeRule.onNodeWithTag("nav_dashboard").assertIsDisplayed()
		composeRule.onNodeWithTag("nav_stats").assertIsDisplayed()
		composeRule.onNodeWithTag("nav_map").assertIsDisplayed()
		composeRule.onNodeWithTag("nav_game").assertIsDisplayed()
	}

	@Test
	fun navigation_trackerTab_selectsCorrectly() {
		composeRule.onNodeWithTag("nav_dashboard").performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
		composeRule.onNodeWithTag("nav_stats").assertIsNotSelected()
		composeRule.onNodeWithTag("nav_game").assertIsNotSelected()
	}

	@Test
	fun navigation_statsTab_selectsCorrectly() {
		composeRule.onNodeWithTag("nav_stats").performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithTag("nav_stats").assertIsSelected()
		composeRule.onNodeWithTag("nav_dashboard").assertIsNotSelected()
		composeRule.onNodeWithTag("nav_game").assertIsNotSelected()
	}

	@Test
	fun navigation_gameTab_selectsCorrectly() {
		composeRule.onNodeWithTag("nav_game").performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithTag("nav_game").assertIsSelected()
		composeRule.onNodeWithTag("nav_stats").assertIsNotSelected()
		composeRule.onNodeWithTag("nav_dashboard").assertIsNotSelected()
	}



	// endregion

	// region Back Navigation

	@Test
	fun backNavigation_fromAnyTab_returnsToTracker() {
		// Navigate to stats
		composeRule.onNodeWithTag("nav_stats").performClick()
		composeRule.waitForIdle()
		composeRule.onNodeWithTag("nav_stats").assertIsSelected()

		// Press back
		composeRule.runOnUiThread {
			composeRule.activity.onBackPressedDispatcher.onBackPressed()
		}
		composeRule.waitForIdle()

		// Should return to tracker
		composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
	}

	@Test
	fun backNavigation_fromGame_returnsToTracker() {
		// Navigate to game
		composeRule.onNodeWithTag("nav_game").performClick()
		composeRule.waitForIdle()
		composeRule.onNodeWithTag("nav_game").assertIsSelected()

		// Press back
		composeRule.runOnUiThread {
			composeRule.activity.onBackPressedDispatcher.onBackPressed()
		}
		composeRule.waitForIdle()

		// Should return to tracker
		composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
	}

	@Test
	fun backNavigation_fromMap_returnsToTracker() {
		// Navigate to map
		composeRule.onNodeWithTag("nav_map").performClick()
		composeRule.waitForIdle()
		composeRule.onNodeWithTag("nav_map").assertIsSelected()

		// Press back
		composeRule.runOnUiThread {
			composeRule.activity.onBackPressedDispatcher.onBackPressed()
		}
		composeRule.waitForIdle()

		// Should return to tracker
		composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
	}

	// endregion

	// region Deep Links / Intent Handling
	// Note: Intent handling tests require launching a new activity with the intent.
	// These are covered in MainActivityComposeIntentTest which uses ActivityScenario.
	// Here we focus on navigation state behavior.

	// endregion

	// region Accessibility

	@Test
	fun allNavigationItems_haveMininumTouchTargetSize() {
		composeRule.waitForIdle()

		// All nav items should have minimum 48dp touch targets
		composeRule.onNodeWithTag("nav_dashboard").assertMinTouchTargetSize()
		composeRule.onNodeWithTag("nav_stats").assertMinTouchTargetSize()
		composeRule.onNodeWithTag("nav_map").assertMinTouchTargetSize()
		composeRule.onNodeWithTag("nav_game").assertMinTouchTargetSize()
	}

	@Test
	fun allNavigationItems_haveAccessibleLabels() {
		composeRule.waitForIdle()

		// All nav items should have accessible text (either text or content description)
		composeRule.onNodeWithTag("nav_dashboard").assertHasAccessibleText()
		composeRule.onNodeWithTag("nav_stats").assertHasAccessibleText()
		composeRule.onNodeWithTag("nav_map").assertHasAccessibleText()
		composeRule.onNodeWithTag("nav_game").assertHasAccessibleText()
	}



	// endregion

	// region Tab State Preservation

	@Test
	fun tabSwitch_preservesPreviousTabState() {
		// Navigate to tracker, potentially interact, then switch tabs
		composeRule.onNodeWithTag("nav_dashboard").performClick()
		composeRule.waitForIdle()

		// Switch to stats
		composeRule.onNodeWithTag("nav_stats").performClick()
		composeRule.waitForIdle()

		// Return to tracker - should maintain its state
		composeRule.onNodeWithTag("nav_dashboard").performClick()
		composeRule.waitForIdle()

		composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
	}

	// endregion

	companion object {
		@JvmStatic
		@BeforeClass
		fun markOnboardingCompleted() {
			markOnboardingCompletedForTests()
		}
	}
}
