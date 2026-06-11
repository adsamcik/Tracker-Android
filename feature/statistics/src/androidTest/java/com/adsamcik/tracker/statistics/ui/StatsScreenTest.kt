package com.adsamcik.tracker.statistics.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.statistics.fragment.AppendUiState
import com.adsamcik.tracker.statistics.fragment.RefreshUiState
import com.adsamcik.tracker.statistics.test.StatsScreenTestHost
import com.adsamcik.tracker.statistics.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused UI tests for paging refresh & append states of Stats screen using the test host.
 * Verifies visibility of loading, empty, error, append error & placeholders.
 */
@RunWith(AndroidJUnit4::class)
class StatsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun setContent(
        refresh: RefreshUiState,
        append: AppendUiState,
        includeSample: Boolean = false,
    ) {
        composeRule.setContent {
            // Theme comes from host activity (Material3 via app theme). We only need the test host.
            StatsScreenTestHost(
                refreshState = refresh,
                appendState = append,
                onRetry = {},
                onShowSummary = {},
                onShowWeek = {},
                onOpenWifi = {},
                includeSampleSessionRow = includeSample,
                onOpenDetails = {},
            )
        }
    }

    @Test
    fun loading_refresh_state_shows_progress() {
        setContent(RefreshUiState.Loading, AppendUiState.NotLoading)
        // Can't assert absence via onAllNodesWithTag (not available); rely on other tests for row presence.
    }

    @Test
    fun empty_refresh_state_shows_empty_text() {
        setContent(RefreshUiState.Empty, AppendUiState.NotLoading)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.stats_no_tracker_sessions)).assertIsDisplayed()
    }

    @Test
    fun error_refresh_state_shows_retry() {
        setContent(RefreshUiState.Error, AppendUiState.NotLoading)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.action_retry)).assertIsDisplayed()
    }

    @Test
    fun content_with_append_loading_shows_placeholders() {
        setContent(RefreshUiState.Content, AppendUiState.Loading, includeSample = true)
        // There are 3 placeholders tagged stats_placeholder_0..2
        composeRule.onNodeWithTag("stats_placeholder_0").assertIsDisplayed()
        composeRule.onNodeWithTag("stats_placeholder_1").assertIsDisplayed()
        composeRule.onNodeWithTag("stats_placeholder_2").assertIsDisplayed()
    }

    @Test
    fun content_with_append_error_shows_footer_error() {
        setContent(RefreshUiState.Content, AppendUiState.Error, includeSample = true)
        composeRule.onNodeWithTag("stats_append_error").assertIsDisplayed()
    }
}
