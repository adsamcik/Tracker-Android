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
class SummaryDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun summaryDialog_whenVisible_showsDialog() {
        // Arrange
        val testStats = listOf(
            Stat(
                nameRes = R.string.stats_distance,
                iconRes = R.drawable.ic_stats_distance,
                displayType = StatisticDisplayType.Text,
                data = "10.5 km"
            ),
            Stat(
                nameRes = R.string.stats_steps,
                iconRes = R.drawable.ic_stats_steps,
                displayType = StatisticDisplayType.Text,
                data = "1,234"
            )
        )

        // Act
        composeTestRule.setContent {
            SummaryDialog(
                visible = true,
                stats = testStats,
                onDismiss = { }
            )
        }

        // Assert
        composeTestRule.onNodeWithTag("summary_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("10.5 km").assertIsDisplayed()
        composeTestRule.onNodeWithText("1,234").assertIsDisplayed()
    }

    @Test
    fun summaryDialog_whenNotVisible_hidesDialog() {
        // Arrange
        val testStats = listOf(
            Stat(
                nameRes = R.string.stats_distance,
                iconRes = R.drawable.ic_stats_distance,
                displayType = StatisticDisplayType.Text,
                data = "10.5 km"
            )
        )

        // Act
        composeTestRule.setContent {
            SummaryDialog(
                visible = false,
                stats = testStats,
                onDismiss = { }
            )
        }

        // Assert
        composeTestRule.onNodeWithTag("summary_dialog").assertDoesNotExist()
    }

    @Test
    fun summaryDialog_withEmptyStats_showsEmptyMessage() {
        // Act
        composeTestRule.setContent {
            SummaryDialog(
                visible = true,
                stats = emptyList(),
                onDismiss = { }
            )
        }

        // Assert
        composeTestRule.onNodeWithTag("summary_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("No statistics available").assertIsDisplayed()
    }
}