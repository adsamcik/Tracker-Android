package com.adsamcik.tracker.dashboard.ui.compose.cards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationPermissionBannerTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun rendersTitleAndSubtitle() {
		composeRule.setContent {
			AppTheme { LocationPermissionBanner(onRequestPermission = {}) }
		}

		composeRule.onNodeWithText("Location permission unavailable").assertIsDisplayed()
		composeRule.onNodeWithText("grant", substring = true, ignoreCase = true).assertIsDisplayed()
	}

	@Test
	fun tapInvokesRequestCallback() {
		var invoked = 0
		composeRule.setContent {
			AppTheme { LocationPermissionBanner(onRequestPermission = { invoked += 1 }) }
		}

		composeRule.onNodeWithTag("dashboard_permission_banner").performClick()

		invoked shouldBe 1
	}
}
