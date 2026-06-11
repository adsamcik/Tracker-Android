package com.adsamcik.tracker.statistics.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.statistics.fragment.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation variant of placeholder mode test (no paging items) asserting 5 placeholder session rows
 * and absence of append error.
 */
@RunWith(AndroidJUnit4::class)
class StatsScreenPlaceholderTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun placeholderMode_showsFiveSessionRows_andNoAppendError() {
        composeRule.setContent {
            StatsScreen(
                refreshState = RefreshUiState.Content,
                appendState = AppendUiState.NotLoading,
                onRetry = {},
                onShowSummary = {},
                onShowWeek = {},
                onOpenWifi = {},
                sessions = null
            )
        }
        composeRule.onAllNodesWithTag("stats_session_row").assertCountEquals(5)
        composeRule.onAllNodesWithTag("stats_append_error").assertCountEquals(0)
    }
}
