package com.adsamcik.tracker.app.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.ui.FloatingNavigationBar
import com.adsamcik.tracker.app.ui.NavigationItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingNavigationBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val items = listOf(
        NavigationItem(id = "map", icon = Icons.Default.Map, contentDescription = "Map", testTag = "nav_map"),
        NavigationItem(id = "stats", icon = Icons.Default.Timeline, contentDescription = "Stats", testTag = "nav_stats"),
        NavigationItem(id = "settings", icon = Icons.Default.Settings, contentDescription = "Settings", testTag = "nav_settings"),
    )

    @Test
    fun displaysAllItems() {
        composeTestRule.setContent {
            AppTheme {
                FloatingNavigationBar(
                    items = items,
                    selectedItem = items[0],
                    onItemClick = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Map").assertIsDisplayed()
        composeTestRule.onNodeWithText("Stats").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun clickItem_callsCallback() {
        var clickedItem: NavigationItem? = null
        composeTestRule.setContent {
            AppTheme {
                FloatingNavigationBar(
                    items = items,
                    selectedItem = items[0],
                    onItemClick = { clickedItem = it },
                )
            }
        }
        composeTestRule.onNodeWithTag("nav_settings").performClick()
        clickedItem shouldBe items[2]
    }

    @Test
    fun testTags_areSet() {
        composeTestRule.setContent {
            AppTheme {
                FloatingNavigationBar(
                    items = items,
                    selectedItem = items[0],
                    onItemClick = {},
                )
            }
        }
        composeTestRule.onNodeWithTag("nav_map").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_stats").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_settings").assertIsDisplayed()
    }
}
