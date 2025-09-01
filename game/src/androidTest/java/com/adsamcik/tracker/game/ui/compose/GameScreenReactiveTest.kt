package com.adsamcik.tracker.game.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.game.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameScreenReactiveTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<HostTestActivity>()

    @Test
    fun updates_whenStateChanges() {
        var points by mutableStateOf(1)
        var steps by mutableStateOf(StepsSummaryUi(10, 100, 100, 1000))
        var challenges by mutableStateOf(listOf(
            ChallengeUi(1, "A", "", 0.1f)
        ))

        composeRule.setContent {
            GameScreen(pointsToday = points, steps = steps, challenges = challenges)
        }

        // Initial assertions
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("10 / 100").assertIsDisplayed()
        composeRule.onNodeWithText("A").assertIsDisplayed()

        // Update state and assert recomposition
        composeRule.runOnIdle {
            points = 99
            steps = StepsSummaryUi(1234, 5678, 2000, 14000)
            challenges = listOf(
                ChallengeUi(2, "B", "desc", 0.8f),
                ChallengeUi(3, "C", "desc2", 0.2f)
            )
        }

        composeRule.onNodeWithText("99").assertIsDisplayed()
        composeRule.onNodeWithText("1234 / 2000").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.challenge_list_title)).assertIsDisplayed()
        composeRule.onNodeWithText("B").assertIsDisplayed()
        composeRule.onNodeWithText("C").assertIsDisplayed()
    }
}
