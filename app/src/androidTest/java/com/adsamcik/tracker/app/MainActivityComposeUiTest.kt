package com.adsamcik.tracker.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.activity.MainActivityCompose
import com.adsamcik.tracker.app.testing.markOnboardingCompletedForTests
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityComposeUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivityCompose>()

    private val context get() = composeRule.activity

    @Before
    fun ensureOnboardingCompleted() {
        markOnboardingCompletedForTests()
    }

    @Test
    fun smoke_launches_map_selected_by_default() {
        val expandedDescription = context.getString(R.string.main_nav_map_state_expanded)

        composeRule.onNodeWithTag("nav_map")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expandedDescription))
    }

    @Test
    fun bottom_nav_navigates_between_tabs_and_back_goes_to_map() {
        val collapsedDescription = context.getString(R.string.main_nav_map_state_collapsed)

        composeRule.onNodeWithTag("nav_stats").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_stats").assertIsSelected()
        composeRule.onNodeWithTag("nav_map")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsedDescription))

        composeRule.onNodeWithTag("nav_game").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_game").assertIsSelected()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nav_map").assertIsSelected()
    }

    @Test
    fun intent_extra_openGame_opens_game_tab_on_start() {
        val collapsedDescription = context.getString(R.string.main_nav_map_state_collapsed)

        composeRule.runOnIdle {
            val intent = Intent(context, MainActivityCompose::class.java).apply {
                putExtra("openGame", true)
            }
            composeRule.activity.onNewIntent(intent)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_game").assertIsSelected()
        composeRule.onNodeWithTag("nav_map")
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, collapsedDescription))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun markOnboardingCompleted() {
            markOnboardingCompletedForTests()
        }
    }
}
