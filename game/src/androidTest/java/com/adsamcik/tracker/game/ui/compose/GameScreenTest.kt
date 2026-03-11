package com.adsamcik.tracker.game.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.game.ui.compose.HostTestActivity
import com.adsamcik.tracker.game.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<HostTestActivity>()

    @Test
    fun rendersPointsStepsAndChallenges() {
        val steps = StepsSummaryUi(stepsToday = 1234, stepsWeek = 5678, goalDay = 2000, goalWeek = 14000)
        val challenges = listOf(
            ChallengeUi(1, title = "Explorer", description = "Visit 5 new areas", progress = 0.4f),
            ChallengeUi(2, title = "Walk 3 km", description = "Distance walking", progress = 0.7f)
        )

        composeRule.setContent {
            GameScreen(pointsToday = 42, steps = steps, challenges = challenges)
        }

        // Points
        composeRule.onNodeWithText("42").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.points_earned_today)).assertIsDisplayed()

        // Steps labels
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Week").assertIsDisplayed()
        composeRule.onNodeWithText("1234 / 2000").assertIsDisplayed()
        composeRule.onNodeWithText("5678 / 14000").assertIsDisplayed()

        // Challenges
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.challenge_list_title)).assertIsDisplayed()
        composeRule.onNodeWithText("Explorer").assertIsDisplayed()
        composeRule.onNodeWithText("Walk 3 km").assertIsDisplayed()
    }

    @Test
    fun showsTodayScopeEvenWhenPointsAreZero() {
        composeRule.setContent {
            GameScreen(
                pointsToday = 0,
                steps = StepsSummaryUi(stepsToday = 0, stepsWeek = 0, goalDay = 1000, goalWeek = 7000),
                challenges = emptyList(),
            )
        }

        composeRule.onNodeWithText("0").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.points_earned_today))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.game_track_and_earn_points_action)
        ).assertIsDisplayed()
    }
}
