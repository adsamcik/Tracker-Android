package com.adsamcik.tracker.statistics.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sampleStats = listOf(
        Stat(
            nameRes = R.string.stats_distance_total,
            iconRes = 0,
            displayType = StatisticDisplayType.INFORMATION,
            data = "10.5 km"
        ),
        Stat(
            nameRes = R.string.stats_steps,
            iconRes = 0,
            displayType = StatisticDisplayType.INFORMATION,
            data = "1,234"
        )
    )

    private fun setContent(visible: Boolean, stats: List<Stat>) {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface {
                    SummaryDialog(
                        visible = visible,
                        stats = stats,
                        onDismiss = {}
                    )
                }
            }
        }
    }

    @Test
    fun summaryDialog_whenVisible_showsDialog() {
        setContent(visible = true, stats = sampleStats)

        composeRule.onNodeWithTag("summaryDialog").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.stats_sum_title)).assertIsDisplayed()
        composeRule.onNodeWithText("10.5 km").assertIsDisplayed()
        composeRule.onNodeWithText("1,234").assertIsDisplayed()
    }

    @Test
    fun summaryDialog_whenNotVisible_hidesDialog() {
        setContent(visible = false, stats = sampleStats)

        composeRule.onAllNodesWithTag("summaryDialog").assertCountEquals(0)
    }

    @Test
    fun summaryDialog_withEmptyStats_showsDismissButton() {
        setContent(visible = true, stats = emptyList())

        composeRule.onNodeWithTag("summaryDialog").assertIsDisplayed()
        composeRule.onNodeWithTag("summaryDialog_dismiss").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(android.R.string.ok)).assertIsDisplayed()
    }
}
