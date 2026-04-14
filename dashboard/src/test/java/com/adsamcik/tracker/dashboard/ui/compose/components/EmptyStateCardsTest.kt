package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
	}

	@Test
	fun emptyStateStartHintCard_showsHintContent() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateStartHintCard()
			}
		}

		composeRule.onNodeWithText("Ready when you are").assertIsDisplayed()
	}
}
