package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmptyStateCardsTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun gettingStartedCard_showsQuickStartContent() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GettingStartedCard()
			}
		}

		composeRule.onNodeWithText("Quick start", substring = true).assertIsDisplayed()
		composeRule.onAllNodesWithText("floating action button", substring = true)
			.assertCountEquals(0)
	}

	@Test
	fun emptyStateStartHintCard_withPermissionShowsStartHint() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateStartHintCard(onStart = {}, hasPermission = true)
			}
		}

		composeRule.onNodeWithText("Ready when you are").assertIsDisplayed()
		composeRule.onNodeWithText("Tap here to start your first tracking session.").assertIsDisplayed()
	}

	@Test
	fun emptyStateStartHintCard_withoutPermissionShowsGrantLocationHint() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateStartHintCard(onStart = {}, hasPermission = false)
			}
		}

		composeRule.onNodeWithText("Ready when you are").assertIsDisplayed()
		composeRule.onNodeWithText(
			"Tap here to grant location access, then start your first tracking session.",
		).assertIsDisplayed()
	}
}
