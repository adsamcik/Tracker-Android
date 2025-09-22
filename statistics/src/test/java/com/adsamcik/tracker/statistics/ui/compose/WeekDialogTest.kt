package com.adsamcik.tracker.statistics.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.data.Stat
import com.adsamcik.tracker.statistics.detail.StatisticDisplayType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeekDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun weekDialog_whenVisible_showsDialog() {
        // Arrange
        val testStats = listOf(
            Stat(
                nameRes = R.string.stats_distance,
                iconRes = R.drawable.ic_stats_distance,
                displayType = StatisticDisplayType.Text,
                data = "50.2 km"
            ),
            Stat(
                nameRes = R.string.stats_sessions,
                iconRes = R.drawable.ic_stats_session_count,
                displayType = StatisticDisplayType.Text,
                data = "7"
            )
        )

        // Act
        composeTestRule.setContent {
            WeekDialog(
                visible = true,
                stats = testStats,
                onDismiss = { }
            )
        }

        // Assert
        composeTestRule.onNodeWithTag("week_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("50.2 km").assertIsDisplayed()
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
    }

    @Test
    fun weekDialog_whenNotVisible_hidesDialog() {
        // Arrange
        val testStats = listOf(
            Stat(
                nameRes = R.string.stats_distance,
                iconRes = R.drawable.ic_stats_distance,
                displayType = StatisticDisplayType.Text,
                data = "50.2 km"
            )
        )

        // Act
        composeTestRule.setContent {
            WeekDialog(
                visible = false,
                stats = testStats,
                onDismiss = { }
            )
        }

        // Assert
        composeTestRule.onNodeWithTag("week_dialog").assertDoesNotExist()
    }

    @Test
    fun weekDialog_withEmptyStats_showsEmptyMessage() {
        // Act
        composeTestRule.setContent {
            WeekDialog(
                visible = true,
                stats = emptyList(),
                onDismiss = { }
            )
        }

        // Assert
        composeTestRule.onNodeWithTag("week_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("No weekly statistics available").assertIsDisplayed()
    }
}