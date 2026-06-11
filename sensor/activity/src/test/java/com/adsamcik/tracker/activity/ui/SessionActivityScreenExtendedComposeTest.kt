package com.adsamcik.tracker.activity.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.adsamcik.tracker.shared.base.data.SessionActivity
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActivityScreenExtendedComposeTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun setScreen(
		items: List<SessionActivity> = emptyList(),
		onNavigateBack: (() -> Unit)? = null,
		onAddActivity: () -> Unit = {},
		onEditActivity: (SessionActivity) -> Unit = {},
		onDeleteActivity: (SessionActivity) -> Unit = {},
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionActivityScreen(
					items = items,
					snackbarHostState = SnackbarHostState(),
					onNavigateBack = onNavigateBack,
					onAddActivity = onAddActivity,
					onEditActivity = onEditActivity,
					onDeleteActivity = onDeleteActivity,
				)
			}
		}
	}

	// --- Navigation back button tests ---

	@Test
	fun `back button is hidden when onNavigateBack is null`() {
		setScreen(onNavigateBack = null)
		composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
	}

	@Test
	fun `back button is shown when onNavigateBack is provided`() {
		setScreen(onNavigateBack = {})
		composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
	}

	@Test
	fun `back button click calls onNavigateBack`() {
		var backCalled = false
		setScreen(onNavigateBack = { backCalled = true })
		composeRule.onNodeWithContentDescription("Back").performClick()
		composeRule.waitForIdle()
		assertTrue(backCalled)
	}

	// --- Edit icon visibility ---

	@Test
	fun `edit icon shown for user-created activity`() {
		setScreen(items = listOf(SessionActivity(id = 5, name = "Hiking")))
		composeRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
	}

	@Test
	fun `edit icon hidden for system activity with negative id`() {
		setScreen(items = listOf(SessionActivity(id = -1, name = "Unknown")))
		composeRule.onNodeWithContentDescription("Edit").assertDoesNotExist()
	}

	// --- Multiple items ---

	@Test
	fun `displays multiple activity items in order`() {
		val activities = listOf(
			SessionActivity(id = 1, name = "Walking"),
			SessionActivity(id = 2, name = "Running"),
			SessionActivity(id = 3, name = "Cycling"),
		)
		setScreen(items = activities)
		composeRule.onNodeWithText("Walking").assertIsDisplayed()
		composeRule.onNodeWithText("Running").assertIsDisplayed()
		composeRule.onNodeWithText("Cycling").assertIsDisplayed()
	}

	@Test
	fun `system activity is not clickable for edit`() {
		var editedActivity: SessionActivity? = null
		setScreen(
			items = listOf(SessionActivity(id = -1, name = "System")),
			onEditActivity = { editedActivity = it },
		)
		// onEdit is null for id < 0, so clicking should not trigger callback
		composeRule.onNodeWithText("System").performClick()
		composeRule.waitForIdle()
		assertNull(editedActivity)
	}
}
