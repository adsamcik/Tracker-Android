package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.dashboard.data.DashboardWidget
import com.adsamcik.tracker.dashboard.data.ResolvedWidget
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomizeDashboardSheetTest {

	@get:Rule
	val composeRule = createComposeRule()

	private val sampleWidgets = listOf(
		ResolvedWidget(widget = DashboardWidget.TodayProgress, visible = true),
		ResolvedWidget(widget = DashboardWidget.Streak, visible = true),
		ResolvedWidget(widget = DashboardWidget.Challenges, visible = false),
	)

	@Test
	fun sheet_displaysTitle() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = sampleWidgets,
					onReorder = {},
					onToggleVisibility = {},
					onResetToDefault = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Customize Dashboard").assertIsDisplayed()
	}

	@Test
	fun sheet_displaysAllWidgetTitles() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = sampleWidgets,
					onReorder = {},
					onToggleVisibility = {},
					onResetToDefault = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Today's Progress", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("Streak", substring = true).assertIsDisplayed()
		composeRule.onNodeWithText("Challenges", substring = true).assertIsDisplayed()
	}

	@Test
	fun sheet_displaysResetButton() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = sampleWidgets,
					onReorder = {},
					onToggleVisibility = {},
					onResetToDefault = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Reset to Default").assertIsDisplayed()
	}

	@Test
	fun sheet_resetButton_invokesCallback() {
		var resetClicked = false
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = sampleWidgets,
					onReorder = {},
					onToggleVisibility = {},
					onResetToDefault = { resetClicked = true },
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Reset to Default").performClick()
		assertTrue("Reset callback should fire", resetClicked)
	}

	@Test
	fun sheet_toggleVisibility_invokesCallbackWithWidgetId() {
		val toggledIds = mutableListOf<String>()
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = sampleWidgets,
					onReorder = {},
					onToggleVisibility = { toggledIds.add(it) },
					onResetToDefault = {},
					onDismiss = {},
				)
			}
		}

		// Find the toggle for Challenges (visible = false) by its content description
		composeRule.onNodeWithContentDescription("Toggle Challenges visibility", substring = true)
			.performClick()

		assertEquals("Should toggle challenges widget", "challenges", toggledIds.firstOrNull())
	}

	@Test
	fun sheet_emptyList_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = emptyList(),
					onReorder = {},
					onToggleVisibility = {},
					onResetToDefault = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Customize Dashboard").assertIsDisplayed()
		composeRule.onNodeWithText("Reset to Default").assertIsDisplayed()
	}

	@Test
	fun sheet_allWidgetsRendered_fullList() {
		val allWidgets = DashboardWidget.all.map { ResolvedWidget(it, visible = true) }
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				CustomizeDashboardSheet(
					resolvedWidgets = allWidgets,
					onReorder = {},
					onToggleVisibility = {},
					onResetToDefault = {},
					onDismiss = {},
				)
			}
		}

		composeRule.onNodeWithText("Customize Dashboard").assertIsDisplayed()
	}
}
