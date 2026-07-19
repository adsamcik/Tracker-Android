package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.app.settings.game.GameSettingsContent
import com.adsamcik.tracker.game.goals.settings.GoalsSettingsState
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameSettingsScreenTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	@Test
	fun displaysAllSettingsSections() {
		composeTestRule.setContent {
			AppTheme {
				GameSettingsContent(settings = GoalsSettingsState.defaults())
			}
		}

		composeTestRule.onNodeWithText("Goals").assertIsDisplayed()
		composeTestRule.onNodeWithText("Notifications").assertIsDisplayed()
		assertTextAfterScroll("During games")
		assertTextAfterScroll("Privacy & battery")
	}

	@Test
	fun exposesPrivacyAndLocationDisclosure() {
		composeTestRule.setContent {
			AppTheme {
				GameSettingsContent(settings = GoalsSettingsState.defaults())
			}
		}

		assertTextAfterScroll("Games use live location", substring = true)
		assertTextAfterScroll("Pausing releases", substring = true)
	}

	private fun assertTextAfterScroll(text: String, substring: Boolean = false) {
		composeTestRule.onNode(hasScrollAction())
			.performScrollToNode(hasText(text, substring = substring))
		composeTestRule.onNodeWithText(text, substring = substring).assertIsDisplayed()
	}
}
