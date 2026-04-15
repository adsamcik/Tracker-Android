package com.adsamcik.tracker.app.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the game settings screen layout.
 * Since GameSettingsScreen uses hiltViewModel(), we test with a reconstructed layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun GameSettingsTestLayout(
        challengeEnabled: Boolean = true,
        onChallengeEnabledChanged: (Boolean) -> Unit = {},
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            // Challenges section
            item {
                SectionHeader("Challenges")
            }
            item {
                SwitchSettingsItem(
                    title = "Enable challenges",
                    checked = challengeEnabled,
                    onCheckedChange = onChallengeEnabledChanged,
                )
            }
            // Goals section
            item {
                SectionHeader("Goals")
            }
            item {
                Text(
                    text = "Goals are managed in the game tab",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    @Test
    fun displaysChallengesSection() {
        composeTestRule.setContent {
            AppTheme { GameSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Challenges").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable challenges").assertIsDisplayed()
    }

    @Test
    fun displaysGoalsSection() {
        composeTestRule.setContent {
            AppTheme { GameSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Goals").assertIsDisplayed()
        composeTestRule.onNodeWithText("managed", substring = true).assertIsDisplayed()
    }

    @Test
    fun challengeToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                GameSettingsTestLayout(
                    challengeEnabled = true,
                    onChallengeEnabledChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Enable challenges").performClick()
        newValue shouldBe false
    }

    @Test
    fun challengeDisabledTogglesOn() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                GameSettingsTestLayout(
                    challengeEnabled = false,
                    onChallengeEnabledChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Enable challenges").performClick()
        newValue shouldBe true
    }
}
