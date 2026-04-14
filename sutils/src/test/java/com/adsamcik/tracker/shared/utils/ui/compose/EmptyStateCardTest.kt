package com.adsamcik.tracker.shared.utils.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
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
	fun `displays title and subtitle`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateCard(
					icon = Icons.Default.Search,
					title = "No results",
					subtitle = "Try a different search",
				)
			}
		}

		composeRule.onNodeWithText("No results").assertIsDisplayed()
		composeRule.onNodeWithText("Try a different search").assertIsDisplayed()
	}

	@Test
	fun `displays action button when provided`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateCard(
					icon = Icons.Default.Search,
					title = "Empty",
					subtitle = "Nothing here",
					action = {
						Button(onClick = {}) {
							Text("Retry")
						}
					},
				)
			}
		}

		composeRule.onNodeWithText("Retry").assertIsDisplayed()
	}

	@Test
	fun `renders without action slot`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateCard(
					icon = Icons.Default.Search,
					title = "Title Only",
					subtitle = "Subtitle Only",
				)
			}
		}

		composeRule.onNodeWithText("Title Only").assertIsDisplayed()
		composeRule.onNodeWithText("Subtitle Only").assertIsDisplayed()
	}

	@Test
	fun `icon is rendered without crash`() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				EmptyStateCard(
					icon = Icons.Default.Search,
					title = "With Icon",
					subtitle = "Icon should render",
				)
			}
		}

		composeRule.onNodeWithText("With Icon").assertIsDisplayed()
	}
}
