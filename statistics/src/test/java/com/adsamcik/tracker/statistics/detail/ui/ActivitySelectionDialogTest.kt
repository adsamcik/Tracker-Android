package com.adsamcik.tracker.statistics.detail.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.shared.base.data.SessionActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for [ActivitySelectionDialog].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivitySelectionDialogTest {

	@get:Rule
	val composeTestRule = createComposeRule()

	// ─── Empty list ─────────────────────────────────────────────────────

	@Test
	fun `empty activities shows empty text`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = emptyList(),
					selectedActivityId = null,
					onActivitySelected = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithTag("activity_selection_empty")
			.assertIsDisplayed()
	}

	// ─── Non-empty list ─────────────────────────────────────────────────

	@Test
	fun `activities list shows all items`() {
		val activities = listOf(
			SessionActivity(id = 1L, name = "Walking"),
			SessionActivity(id = 2L, name = "Running"),
			SessionActivity(id = 3L, name = "Cycling"),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = activities,
					selectedActivityId = 1L,
					onActivitySelected = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Walking").assertIsDisplayed()
		composeTestRule.onNodeWithText("Running").assertIsDisplayed()
		composeTestRule.onNodeWithText("Cycling").assertIsDisplayed()
	}

	// ─── Selection callback ─────────────────────────────────────────────

	@Test
	fun `tapping item triggers onActivitySelected`() {
		var selectedId: Long? = null
		val activities = listOf(
			SessionActivity(id = 10L, name = "Hiking"),
			SessionActivity(id = 20L, name = "Swimming"),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = activities,
					selectedActivityId = null,
					onActivitySelected = { selectedId = it.id },
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Swimming")
			.performClick()
		assert(selectedId == 20L) { "Expected 20 but got $selectedId" }
	}

	// ─── Pre-selected item ──────────────────────────────────────────────

	@Test
	fun `pre-selected activity has radio button selected`() {
		val activities = listOf(
			SessionActivity(id = 1L, name = "Walk"),
			SessionActivity(id = 2L, name = "Run"),
		)
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = activities,
					selectedActivityId = 2L,
					onActivitySelected = {},
					onDismiss = {},
				)
			}
		}
		// Both items should be visible
		composeTestRule.onNodeWithTag("activity_item_1").assertIsDisplayed()
		composeTestRule.onNodeWithTag("activity_item_2").assertIsDisplayed()
	}

	// ─── Dismiss ────────────────────────────────────────────────────────

	@Test
	fun `close button calls onDismiss`() {
		var dismissed = false
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = emptyList(),
					selectedActivityId = null,
					onActivitySelected = {},
					onDismiss = { dismissed = true },
				)
			}
		}
		composeTestRule.onNodeWithTag("activity_selection_close")
			.performClick()
		assert(dismissed) { "onDismiss should have been called" }
	}

	// ─── Single activity ────────────────────────────────────────────────

	@Test
	fun `single activity renders correctly`() {
		val activities = listOf(SessionActivity(id = 99L, name = "Skiing"))
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = activities,
					selectedActivityId = 99L,
					onActivitySelected = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithText("Skiing").assertIsDisplayed()
	}

	// ─── Dialog tag ─────────────────────────────────────────────────────

	@Test
	fun `dialog has correct test tag`() {
		composeTestRule.setContent {
			MaterialTheme(colorScheme = lightColorScheme()) {
				ActivitySelectionDialog(
					activities = emptyList(),
					selectedActivityId = null,
					onActivitySelected = {},
					onDismiss = {},
				)
			}
		}
		composeTestRule.onNodeWithTag("activity_selection_dialog")
			.assertIsDisplayed()
	}
}
