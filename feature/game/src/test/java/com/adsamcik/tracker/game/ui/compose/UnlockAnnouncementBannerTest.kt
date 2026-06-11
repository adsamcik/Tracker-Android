package com.adsamcik.tracker.game.ui.compose

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
class UnlockAnnouncementBannerTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun withFeatureName_showsBanner() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				UnlockAnnouncementBanner(
					featureName = "Mini Games",
					newLevel = 5,
				)
			}
		}

		composeRule.onNodeWithText("New Unlock!").assertIsDisplayed()
		composeRule.onNodeWithText("You reached Level 5!").assertIsDisplayed()
		composeRule.onNodeWithText("Mini Games").assertIsDisplayed()
	}

	@Test
	fun nullFeatureName_showsNothing() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				UnlockAnnouncementBanner(
					featureName = null,
					newLevel = 3,
				)
			}
		}

		composeRule.onNodeWithText("New Unlock!")
			.assertDoesNotExist()
	}
}
