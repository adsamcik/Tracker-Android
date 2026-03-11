package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmptyStateCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun rendersCoreEmptyStateMessaging() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateCard()
			}
		}

		composeRule.onNodeWithText("Your Journey Starts Here").assertIsDisplayed()
		composeRule.onNodeWithText("Tap the button below to begin tracking your first adventure").assertIsDisplayed()
		composeRule.onNodeWithText("Privacy").assertIsDisplayed()
	}
}
