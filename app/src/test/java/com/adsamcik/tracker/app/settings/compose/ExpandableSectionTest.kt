package com.adsamcik.tracker.app.settings.compose

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpandableSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysTitle() {
        composeTestRule.setContent {
            AppTheme {
                ExpandableSection(title = "Advanced") {
                    Text("Hidden content")
                }
            }
        }
        composeTestRule.onNodeWithText("Advanced").assertIsDisplayed()
    }

    @Test
    fun collapsedByDefault_contentNotVisible() {
        composeTestRule.setContent {
            AppTheme {
                ExpandableSection(title = "Advanced") {
                    Text("Hidden content")
                }
            }
        }
        composeTestRule.onNodeWithText("Hidden content").assertDoesNotExist()
    }

    @Test
    fun clickExpands_showsContent() {
        composeTestRule.setContent {
            AppTheme {
                ExpandableSection(title = "Advanced") {
                    Text("Hidden content")
                }
            }
        }
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("Hidden content").assertIsDisplayed()
    }

    @Test
    fun doubleClick_collapsesContent() {
        composeTestRule.setContent {
            AppTheme {
                ExpandableSection(title = "Advanced") {
                    Text("Hidden content")
                }
            }
        }
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("Hidden content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("Hidden content").assertDoesNotExist()
    }

    @Test
    fun initiallyExpanded_showsContent() {
        composeTestRule.setContent {
            AppTheme {
                ExpandableSection(title = "Open", initiallyExpanded = true) {
                    Text("Visible from start")
                }
            }
        }
        composeTestRule.onNodeWithText("Visible from start").assertIsDisplayed()
    }
}
