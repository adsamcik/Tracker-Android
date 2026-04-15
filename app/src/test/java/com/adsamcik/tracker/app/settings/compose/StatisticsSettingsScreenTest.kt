package com.adsamcik.tracker.app.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the statistics settings screen layout.
 * Since StatisticsSettingsScreen uses hiltViewModel(), we test with a reconstructed layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatisticsSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun StatisticsSettingsTestLayout(
        autoUnitSwitch: Boolean = false,
        onAutoUnitSwitchChanged: (Boolean) -> Unit = {},
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            item {
                SwitchSettingsItem(
                    title = "Auto unit switch",
                    subtitle = "Automatically switch units based on distance",
                    checked = autoUnitSwitch,
                    onCheckedChange = onAutoUnitSwitchChanged,
                )
            }
        }
    }

    @Test
    fun displaysAutoUnitSwitchSetting() {
        composeTestRule.setContent {
            AppTheme { StatisticsSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Auto unit switch").assertIsDisplayed()
        composeTestRule.onNodeWithText("Automatically switch", substring = true).assertIsDisplayed()
    }

    @Test
    fun toggleCallsCallback_turnsOn() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                StatisticsSettingsTestLayout(
                    autoUnitSwitch = false,
                    onAutoUnitSwitchChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Auto unit switch").performClick()
        newValue shouldBe true
    }

    @Test
    fun toggleCallsCallback_turnsOff() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                StatisticsSettingsTestLayout(
                    autoUnitSwitch = true,
                    onAutoUnitSwitchChanged = { newValue = it },
                )
            }
        }
        composeTestRule.onNodeWithText("Auto unit switch").performClick()
        newValue shouldBe false
    }
}
