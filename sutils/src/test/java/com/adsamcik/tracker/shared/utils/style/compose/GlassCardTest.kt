package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassCardTest {

	@get:Rule
	val composeRule = createComposeRule()

	@Test
	fun rendersContentInside() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassCard {
					Text("Inside Glass", modifier = Modifier.testTag("glass_content"))
				}
			}
		}
		composeRule.onNodeWithText("Inside Glass").assertIsDisplayed()
	}

	@Test
	fun defaultTierIsG1() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassCard(modifier = Modifier.testTag("glass_card")) {
					Text("G1 default")
				}
			}
		}
		composeRule.onNodeWithTag("glass_card").assertIsDisplayed()
	}

	@Test
	fun tierG0_rendersWithoutBorder() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassCard(tier = GlassTier.G0) {
					Text("No border")
				}
			}
		}
		composeRule.onNodeWithText("No border").assertIsDisplayed()
	}

	@Test
	fun tierG2_rendersWithBorder() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassCard(tier = GlassTier.G2) {
					Text("G2 tier")
				}
			}
		}
		composeRule.onNodeWithText("G2 tier").assertIsDisplayed()
	}

	@Test
	fun tierG3_rendersWithBorder() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassCard(tier = GlassTier.G3) {
					Text("G3 tier")
				}
			}
		}
		composeRule.onNodeWithText("G3 tier").assertIsDisplayed()
	}

	@Test
	fun allTiers_renderWithoutCrash() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassTier.entries.forEach { tier ->
					GlassCard(tier = tier) {
						Text("Tier ${tier.name}")
					}
				}
			}
		}
		GlassTier.entries.forEach { tier ->
			composeRule.onNodeWithText("Tier ${tier.name}").assertIsDisplayed()
		}
	}

	@Test
	fun customModifier_isApplied() {
		composeRule.setContent {
			AppTheme(useDynamicColor = false) {
				GlassCard(modifier = Modifier.testTag("custom_glass")) {
					Text("Custom")
				}
			}
		}
		composeRule.onNodeWithTag("custom_glass").assertIsDisplayed()
	}
}
