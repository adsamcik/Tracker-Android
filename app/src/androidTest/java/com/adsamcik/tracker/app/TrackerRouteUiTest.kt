package com.adsamcik.tracker.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.app.activity.MainActivityCompose
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerRouteUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    @Test
    fun map_route_shows_tracker_fab() {
        // Map is default; TrackerRoute should render and expose the tracking FAB
        composeRule.onNodeWithTag("tracking_fab").assertIsDisplayed()
    }

    @Test
    fun map_route_shows_settings_icon() {
        // Settings icon from TrackerTopBar should be present
        val settingsDesc = composeRule.activity.getString(
            com.adsamcik.tracker.tracker.R.string.description_settings
        )
        composeRule.onNodeWithContentDescription(settingsDesc).assertIsDisplayed()
    }
}
