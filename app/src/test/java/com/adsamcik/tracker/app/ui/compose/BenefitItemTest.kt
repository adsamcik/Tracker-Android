package com.adsamcik.tracker.app.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.adsamcik.tracker.app.onboarding.ui.components.BenefitItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BenefitItemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitleAndDescription() {
        composeTestRule.setContent {
            AppTheme {
                BenefitItem(
                    icon = Icons.Default.LocationOn,
                    title = "Track Routes",
                    description = "Record your walking and cycling routes",
                )
            }
        }
        composeTestRule.onNodeWithText("Track Routes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Record your walking and cycling routes").assertIsDisplayed()
    }

    @Test
    fun displaysWithDifferentIcon() {
        composeTestRule.setContent {
            AppTheme {
                BenefitItem(
                    icon = Icons.Default.Shield,
                    title = "Privacy First",
                    description = "All data stays on your device",
                )
            }
        }
        composeTestRule.onNodeWithText("Privacy First").assertIsDisplayed()
        composeTestRule.onNodeWithText("All data stays on your device").assertIsDisplayed()
    }

    @Test
    fun decorativeIcon_isHiddenFromAccessibilityTree() {
        composeTestRule.setContent {
            AppTheme {
                BenefitItem(
                    icon = Icons.Default.Shield,
                    title = "Privacy First",
                    description = "All data stays on your device",
                )
            }
        }

        composeTestRule.onAllNodes(
            matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun titleAndDescription_mergeIntoSingleAccessibilityNode() {
        composeTestRule.setContent {
            AppTheme {
                BenefitItem(
                    icon = Icons.Default.LocationOn,
                    title = "Track Routes",
                    description = "Record your walking and cycling routes",
                )
            }
        }

        composeTestRule.onAllNodes(
            matcher = hasText("Track Routes") and hasText("Record your walking and cycling routes"),
            useUnmergedTree = false,
        ).assertCountEquals(1)
    }

    @Test
    fun displaysLongDescription() {
        val longDescription = "This is a very long description that should still be displayed " +
                "properly within the benefit item layout without any truncation issues."
        composeTestRule.setContent {
            AppTheme {
                BenefitItem(
                    icon = Icons.Default.LocationOn,
                    title = "Feature",
                    description = longDescription,
                )
            }
        }
        composeTestRule.onNodeWithText("Feature").assertIsDisplayed()
        composeTestRule.onNodeWithText(longDescription).assertIsDisplayed()
    }
}
