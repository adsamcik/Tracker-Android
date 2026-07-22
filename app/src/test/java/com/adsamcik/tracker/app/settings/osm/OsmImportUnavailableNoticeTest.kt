package com.adsamcik.tracker.app.settings.osm

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsmImportUnavailableNoticeTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	@Test
	fun `release settings explain unavailability without an actionable import control`() {
		composeTestRule.setContent {
			AppTheme { OfflinePbfImportUnavailableNotice() }
		}

		composeTestRule.onNodeWithText(
			"Offline map import is temporarily unavailable",
			substring = true,
		).assertIsDisplayed()
		composeTestRule.onAllNodes(
			hasClickAction() and hasText("Import OSM region", substring = true),
		).assertCountEquals(0)
	}
}
