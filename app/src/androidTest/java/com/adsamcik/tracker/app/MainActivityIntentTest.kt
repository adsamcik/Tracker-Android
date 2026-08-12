package com.adsamcik.tracker.app

import android.content.Intent
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for intent-based navigation (deep links, openGame extra).
 * Uses createAndroidComposeRule directly for proper Compose test synchronization.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityIntentTest {

	@get:Rule
	val composeRule = createAndroidComposeRule<MainActivityCompose>()

	@Before
	fun ensureOnboardingCompleted() {
		markOnboardingCompletedForTests()
	}

	@Test
	fun launchWithoutExtras_selectsTrackerByDefault() {
		// Give time for composition to settle
		composeRule.waitForIdle()
		
		// Tracker should be selected by default
		composeRule.onNodeWithTag("nav_dashboard").assertIsSelected()
	}
	
	@Test
	fun navigateToMap_selectsMapTab() {
		composeRule.waitForIdle()
		
		// Click on map tab
		composeRule.onNodeWithTag("nav_map").performClick()
		composeRule.waitForIdle()
		
		// Map should now be selected
		composeRule.onNodeWithTag("nav_map").assertIsSelected()
		composeRule.onNodeWithTag("nav_dashboard").assertIsNotSelected()
	}

	@Test
	fun navigateToStats_selectsStatsTab() {
		composeRule.waitForIdle()
		
		// Click on stats tab
		composeRule.onNodeWithTag("nav_stats").performClick()
		composeRule.waitForIdle()
		
		// Stats should now be selected
		composeRule.onNodeWithTag("nav_stats").assertIsSelected()
		composeRule.onNodeWithTag("nav_dashboard").assertIsNotSelected()
	}

	@Test
	fun navigateToGame_selectsGameTab() {
		composeRule.waitForIdle()
		
		// Click on game tab
		composeRule.onNodeWithTag("nav_game").performClick()
		composeRule.waitForIdle()
		
		// Game should now be selected
		composeRule.onNodeWithTag("nav_game").assertIsSelected()
		composeRule.onNodeWithTag("nav_dashboard").assertIsNotSelected()
	}
}
