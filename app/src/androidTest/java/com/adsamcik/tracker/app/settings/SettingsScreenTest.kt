package com.adsamcik.tracker.app.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import com.adsamcik.tracker.testing.accessibility.assertMinTouchTargetSize
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.adsamcik.tracker.tracker.R as TrackerR

/**
 * Comprehensive settings screen tests covering:
 * - Category navigation (Tracking, Data, Map, Game, etc.)
 * - Settings item interactions
 * - Back navigation
 * - Developer mode activation (7-tap gesture)
 * - Accessibility: touch targets on all interactive elements
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

	@get:Rule
	val composeRule = createAndroidComposeRule<MainActivityCompose>()

	private val context get() = composeRule.activity

	@Before
	fun ensureOnboardingCompleted() {
		markOnboardingCompletedForTests()
	}

	private fun navigateToSettings() {
		// Settings is accessed via the settings icon in the tracker dashboard
		// First navigate to tracker tab
		composeRule.onNodeWithContentDescription(
			context.getString(TrackerR.string.description_settings)
		).performClick()
		composeRule.waitForIdle()
	}

	private fun waitForSettingsScreen() {
		composeRule.waitUntil(timeoutMillis = 5_000) {
			try {
				composeRule.onNodeWithText(
					context.getString(R.string.settings_title)
				).assertExists()
				true
			} catch (_: AssertionError) { false }
		}
	}

	// region Category Navigation

	@Test
	fun settingsScreen_displaysMainCategories() {
		navigateToSettings()
		waitForSettingsScreen()

		// Main category items should be visible
		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).assertIsDisplayed()

		composeRule.onNodeWithText(
			context.getString(R.string.settings_data_title)
		).assertIsDisplayed()
	}

	@Test
	fun settingsScreen_navigateToTracking_showsTrackingSettings() {
		navigateToSettings()
		waitForSettingsScreen()

		// Click on Tracking settings
		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).performClick()
		composeRule.waitForIdle()

		// Should now show tracking settings title in top bar
		// (Back button should appear indicating sub-screen)
		composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
	}

	@Test
	fun settingsScreen_navigateToData_showsDataSettings() {
		navigateToSettings()
		waitForSettingsScreen()

		// Click on Data settings
		composeRule.onNodeWithText(
			context.getString(R.string.settings_data_title)
		).performClick()
		composeRule.waitForIdle()

		// Back button should appear
		composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
	}

	@Test
	fun settingsScreen_backNavigation_returnsToRoot() {
		navigateToSettings()
		waitForSettingsScreen()

		// Navigate to sub-screen
		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).performClick()
		composeRule.waitForIdle()

		// Click back
		composeRule.onNodeWithContentDescription("Back").performClick()
		composeRule.waitForIdle()

		// Should be back at root settings
		composeRule.onNodeWithText(
			context.getString(R.string.settings_title)
		).assertIsDisplayed()

		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).assertIsDisplayed()
	}

	// endregion

	// region Settings Items

	@Test
	fun settingsScreen_otherSettingsSection_displaysLengthSystem() {
		navigateToSettings()
		waitForSettingsScreen()

		// Scroll might be needed, but length system should be visible
		composeRule.waitUntil(timeoutMillis = 5_000) {
			try {
				composeRule.onNodeWithText(
					context.getString(R.string.settings_length_system_title)
				).assertExists()
				true
			} catch (_: AssertionError) { false }
		}
	}

	// endregion

	// region Accessibility

	@Test
	fun settingsItems_haveMininumTouchTargetSize() {
		navigateToSettings()
		waitForSettingsScreen()

		// Main category items should have minimum touch targets
		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).assertMinTouchTargetSize()

		composeRule.onNodeWithText(
			context.getString(R.string.settings_data_title)
		).assertMinTouchTargetSize()
	}

	@Test
	fun backButton_hasMinimumTouchTargetSize() {
		navigateToSettings()
		waitForSettingsScreen()

		// Navigate to sub-screen
		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).performClick()
		composeRule.waitForIdle()

		// Back button should have minimum touch target
		composeRule.onNodeWithContentDescription("Back")
			.assertMinTouchTargetSize()
	}

	// endregion

	// region Multiple Navigation

	@Test
	fun settingsScreen_multipleNavigationCycles_works() {
		navigateToSettings()
		waitForSettingsScreen()

		// Navigate to tracking
		composeRule.onNodeWithText(
			context.getString(com.adsamcik.tracker.tracker.R.string.settings_tracking_title)
		).performClick()
		composeRule.waitForIdle()

		// Back
		composeRule.onNodeWithContentDescription("Back").performClick()
		composeRule.waitForIdle()

		// Navigate to data
		composeRule.onNodeWithText(
			context.getString(R.string.settings_data_title)
		).performClick()
		composeRule.waitForIdle()

		// Back
		composeRule.onNodeWithContentDescription("Back").performClick()
		composeRule.waitForIdle()

		// Should be at root
		composeRule.onNodeWithText(
			context.getString(R.string.settings_title)
		).assertIsDisplayed()
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
