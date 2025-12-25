package com.adsamcik.tracker.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for intent-based navigation (deep links, openGame extra).
 * Uses ActivityScenario to launch activity with custom intents.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityIntentTest {

	@get:Rule
	val composeRule = createEmptyComposeRule()

	@Before
	fun ensureOnboardingCompleted() {
		markOnboardingCompletedForTests()
	}

	@Test
	fun launchWithOpenGameIntent_selectsGameTab() {
		val intent = Intent(
			ApplicationProvider.getApplicationContext(),
			MainActivityCompose::class.java
		).apply {
			putExtra("openGame", true)
		}

		ActivityScenario.launch<MainActivityCompose>(intent).use { scenario ->
			scenario.onActivity { activity ->
				val collapsedDescription = activity.getString(R.string.main_nav_map_state_collapsed)

				composeRule.waitForIdle()

				composeRule.onNodeWithTag("nav_game").assertIsSelected()
				composeRule.onNodeWithTag("nav_stats").assertIsNotSelected()
				composeRule.onNodeWithTag("nav_map")
					.assertIsNotSelected()
					.assert(
						SemanticsMatcher.expectValue(
							SemanticsProperties.StateDescription,
							collapsedDescription
						)
					)
			}
		}
	}

	@Test
	fun launchWithoutExtras_selectsMapByDefault() {
		val intent = Intent(
			ApplicationProvider.getApplicationContext(),
			MainActivityCompose::class.java
		)

		ActivityScenario.launch<MainActivityCompose>(intent).use { scenario ->
			scenario.onActivity { activity ->
				val expandedDescription = activity.getString(R.string.main_nav_map_state_expanded)

				composeRule.waitForIdle()

				composeRule.onNodeWithTag("nav_map")
					.assertIsSelected()
					.assert(
						SemanticsMatcher.expectValue(
							SemanticsProperties.StateDescription,
							expandedDescription
						)
					)
			}
		}
	}
}
