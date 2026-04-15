package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrimaryActionButtonTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun displaysUppercasedText() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(text = "Start", onClick = {})
			}
		}
		composeRule.onNodeWithText("START").assertIsDisplayed()
	}

	@Test
	fun clickInvokesCallback() {
		var clicked = false
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(
					text = "Go",
					onClick = { clicked = true },
					modifier = Modifier.testTag("action_btn"),
				)
			}
		}
		composeRule.onNodeWithTag("action_btn").performClick()
		assertTrue(clicked, "onClick should have been called")
	}

	@Test
	fun disabledButton_doesNotInvokeCallback() {
		var clicked = false
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(
					text = "Disabled",
					onClick = { clicked = true },
					enabled = false,
					modifier = Modifier.testTag("disabled_btn"),
				)
			}
		}
		composeRule.onNodeWithTag("disabled_btn").performClick()
		assertTrue(!clicked, "onClick should not be called when disabled")
	}

	@Test
	fun enabledButton_isDisplayed() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(
					text = "Enabled",
					onClick = {},
					enabled = true,
					modifier = Modifier.testTag("enabled_btn"),
				)
			}
		}
		composeRule.onNodeWithTag("enabled_btn").assertIsDisplayed()
	}

	@Test
	fun withIcon_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(
					text = "Add",
					onClick = {},
					icon = {
						Icon(
							imageVector = Icons.Filled.Add,
							contentDescription = null,
						)
					},
				)
			}
		}
		composeRule.onNodeWithText("ADD").assertIsDisplayed()
	}

	@Test
	fun withoutIcon_rendersWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(text = "No Icon", onClick = {})
			}
		}
		composeRule.onNodeWithText("NO ICON").assertIsDisplayed()
	}

	@Test
	fun customModifier_isApplied() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				PrimaryActionButton(
					text = "Custom",
					onClick = {},
					modifier = Modifier.testTag("custom_btn"),
				)
			}
		}
		composeRule.onNodeWithTag("custom_btn").assertIsDisplayed()
	}
}
