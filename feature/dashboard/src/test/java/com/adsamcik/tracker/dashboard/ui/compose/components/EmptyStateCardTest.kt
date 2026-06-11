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
		composeRule.onNodeWithText(
			"Use the Ready card below to grant location access when needed and start your first adventure",
		).assertIsDisplayed()
		composeRule.onNodeWithText("Privacy").assertIsDisplayed()
	}
}
