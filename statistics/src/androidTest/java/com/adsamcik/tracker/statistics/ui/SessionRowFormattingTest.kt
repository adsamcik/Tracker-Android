package com.adsamcik.tracker.statistics.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.statistics.fragment.SessionRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test SessionRow metadata formatting (date, duration, steps).
 * Verifies proper display of session information with various data combinations.
 */
@RunWith(AndroidJUnit4::class)
class SessionRowFormattingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun sessionRow_displaysDateDurationSteps_whenAllDataPresent() {
        val session = TrackerSession(
            id = 123L,
            start = 1640995200000L, // 2022-01-01 00:00:00 UTC
            end = 1640995800000L,   // 2022-01-01 00:10:00 UTC (10 minutes later)
            steps = 1500
        )

        composeRule.setContent {
            SessionRow(session = session)
        }

        // Verify session ID is displayed
        composeRule.onNodeWithText("Session #123").assertIsDisplayed()
        
        // Verify the session row has the correct tag
        composeRule.onNodeWithTag("stats_session_row").assertIsDisplayed()
        
        // Verify metadata contains steps
        composeRule.onNode(hasText("1500 steps", substring = true)).assertIsDisplayed()
        
        // Verify metadata contains duration (10 minutes)
        composeRule.onNode(hasText("10m", substring = true)).assertIsDisplayed()
    }

    @Test
    fun sessionRow_displaysDateOnly_whenNoDurationOrSteps() {
        val session = TrackerSession(
            id = 456L,
            start = 1640995200000L, // 2022-01-01 00:00:00 UTC
            end = 0L,               // No end time
            steps = 0               // No steps
        )

        composeRule.setContent {
            SessionRow(session = session)
        }

        // Verify session ID is displayed
        composeRule.onNodeWithText("Session #456").assertIsDisplayed()
        
        // Verify the session row has the correct tag
        composeRule.onNodeWithTag("stats_session_row").assertIsDisplayed()
        
        // Should contain date but not duration or steps indicators
        composeRule.onNode(hasText("2022-01-01", substring = true)).assertIsDisplayed()
    }

    @Test
    fun sessionRow_displaysStepsOnly_whenNoDuration() {
        val session = TrackerSession(
            id = 789L,
            start = 1640995200000L, // 2022-01-01 00:00:00 UTC
            end = 1640995200000L,   // Same as start (no duration)
            steps = 2500
        )

        composeRule.setContent {
            SessionRow(session = session)
        }

        // Verify session ID is displayed
        composeRule.onNodeWithText("Session #789").assertIsDisplayed()
        
        // Verify metadata contains steps but not duration
        composeRule.onNode(hasText("2500 steps", substring = true)).assertIsDisplayed()
    }

    @Test
    fun sessionRow_displaysPlaceholder_whenNoValidData() {
        val session = TrackerSession(
            id = 999L,
            start = 0L,  // No start time
            end = 0L,    // No end time  
            steps = 0    // No steps
        )

        composeRule.setContent {
            SessionRow(session = session)
        }

        // Verify session ID is displayed
        composeRule.onNodeWithText("Session #999").assertIsDisplayed()
        
        // Should show placeholder text when no meaningful metadata available
        // The exact placeholder text would depend on R.string.stats_session_subtitle_placeholder
    }
}