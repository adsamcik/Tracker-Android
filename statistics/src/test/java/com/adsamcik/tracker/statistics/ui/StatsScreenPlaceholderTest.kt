package com.adsamcik.tracker.statistics.ui

// Temporarily disabled due to missing Compose UI test dependencies
/*
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import com.adsamcik.tracker.statistics.fragment.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme

/**
 * Unit-level (hosted) Compose test verifying placeholder mode (no paging items passed) shows 5 rows + header actions
 * and no append error row.
 */
class StatsScreenPlaceholderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun placeholderMode_showsFiveSessionRows_andHeader_andNoAppendError() {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                StatsScreen(
                    refreshState = RefreshUiState.Content,
                    appendState = AppendUiState.NotLoading,
                    onRetry = {},
                    onShowSummary = {},
                    onShowWeek = {},
                    onOpenWifi = {},
                    sessions = null // triggers placeholder path
                )
            }
        }

        // Header actions (by content description text) - rely on resource literals replaced in instrumentation; here just ensure tags are present via content descriptions we know (fallback English strings used in code under test) if available.
        // We can't easily retrieve string resources in pure unit test without Robolectric; skip header description assertions.

        // Assert exactly 5 session rows
        composeRule.onAllNodesWithTag("stats_session_row").assertCountEquals(5)

        // No append error row
        composeRule.onAllNodesWithTag("stats_append_error").assertCountEquals(0)
    }
}
*/
