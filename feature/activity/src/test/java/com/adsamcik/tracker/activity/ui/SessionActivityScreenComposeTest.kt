package com.adsamcik.tracker.activity.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.activity.data.SessionActivityItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionActivityScreenComposeTest {

	@get:Rule
	val composeRule = createComposeRule()

	private fun setScreen(
		items: List<SessionActivityItem> = emptyList(),
		onAddActivity: () -> Unit = {},
		onEditActivity: (SessionActivityItem) -> Unit = {},
		onDeleteActivity: (SessionActivityItem) -> Unit = {},
	) {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				SessionActivityScreen(
					items = items,
					snackbarHostState = SnackbarHostState(),
					onNavigateBack = null,
					onAddActivity = onAddActivity,
					onEditActivity = onEditActivity,
					onDeleteActivity = onDeleteActivity,
				)
			}
		}
	}

	@Test
	fun `displays activity items`() {
		val activities = listOf(
			SessionActivityItem(id = 1, name = "Walking"),
			SessionActivityItem(id = 2, name = "Running"),
		)

		setScreen(items = activities)

		composeRule.onNodeWithText("Walking").assertIsDisplayed()
		composeRule.onNodeWithText("Running").assertIsDisplayed()
	}

	@Test
	fun `FAB click calls onAddActivity`() {
		var addClicked = false
		setScreen(onAddActivity = { addClicked = true })

		composeRule.onNodeWithContentDescription("Add").performClick()
		assertTrue(addClicked)
	}

	@Test
	fun `empty list shows no items`() {
		setScreen(items = emptyList())

		composeRule.onNodeWithText("Walking").assertDoesNotExist()
	}

	@Test
	fun `tapping activity item calls onEditActivity`() {
		var editedActivity: SessionActivityItem? = null
		val activity = SessionActivityItem(id = 1, name = "Cycling")

		setScreen(
			items = listOf(activity),
			onEditActivity = { editedActivity = it },
		)

		composeRule.onNodeWithText("Cycling").performClick()
		assertTrue(editedActivity != null)
	}
}
