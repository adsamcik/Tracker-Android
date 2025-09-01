package com.adsamcik.tracker.app

import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.activity.MainActivityCompose
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityComposeUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    @Test
    fun smoke_launches_map_selected_by_default() {
        // Map should be selected by default
        composeRule.onNodeWithTag("nav_map").assertIsSelected()
    }

    @Test
    fun bottom_nav_navigates_between_tabs_and_back_goes_to_map() {
        // Navigate to Stats
        composeRule.onNodeWithTag("nav_stats").performClick()
        composeRule.onNodeWithTag("nav_stats").assertIsSelected()
        // Navigate to Game
        composeRule.onNodeWithTag("nav_game").performClick()
        composeRule.onNodeWithTag("nav_game").assertIsSelected()
    // Press back should go to Map (custom back handler)
    composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.onNodeWithTag("nav_map").assertIsSelected()
    }

    @Test
    fun intent_extra_openGame_opens_game_tab_on_start() {
        val context = composeRule.activity
        val intent = Intent(context, MainActivityCompose::class.java).apply {
            putExtra("openGame", true)
        }
        composeRule.activity.startActivity(intent)
        // After intent, Game should be selected
        composeRule.onNodeWithTag("nav_game").assertIsSelected()
    }
}
