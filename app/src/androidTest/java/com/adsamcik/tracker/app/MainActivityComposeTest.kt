package com.adsamcik.tracker.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.activity.MainActivityCompose
import org.junit.Rule
import org.junit.Test

class MainActivityComposeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    @Test
    fun stats_and_game_overlays_toggle() {
        // Stats overlay appears when stats tab clicked
        composeRule.onNodeWithTag("btn_stats").performClick()
        composeRule.onNodeWithTag("overlay_stats").assertIsDisplayed()

        // Game overlay appears when game tab clicked
        composeRule.onNodeWithTag("btn_game").performClick()
        composeRule.onNodeWithTag("overlay_game").assertIsDisplayed()
    }

    @Test
    fun map_expand_and_collapse() {
        // Expand map
        composeRule.onNodeWithTag("btn_map").performClick()
        composeRule.onNodeWithTag("overlay_map_visible").assertIsDisplayed()
        // Collapse map
        composeRule.onNodeWithTag("btn_map").performClick()
        // After collapse, map marker should be absent
        composeRule.onNodeWithTag("overlay_map_visible").assertDoesNotExist()
    }
}
