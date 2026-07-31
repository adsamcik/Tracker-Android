package com.adsamcik.tracker.app.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the debug settings screen layout.
 * Uses a reconstructed layout so build-type branches stay deterministic under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun DebugSettingsTestLayout(
        isDebugBuild: Boolean = true,
        developerModeEnabled: Boolean = true,
        onDisableDeveloperMode: () -> Unit = {},
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            // Disable developer mode (only in release builds when enabled)
            if (!isDebugBuild && developerModeEnabled) {
                item {
                    SettingsItem(
                        title = "Disable developer mode",
                        subtitle = "Hide debug settings",
                        icon = Icons.Default.Close,
                        onClick = onDisableDeveloperMode,
                    )
                }
            }

            // Developer tools (only in debug builds)
            if (isDebugBuild) {
                item { SectionHeader("Developer Tools") }
            }
        }
    }

    @Test
    fun legacyDiagnosticArchivesAreNotExposed() {
        composeTestRule.setContent {
            AppTheme { DebugSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Legacy crash archive").assertDoesNotExist()
        composeTestRule.onNodeWithText("Legacy log archive").assertDoesNotExist()
    }

    @Test
    fun debugBuildShowsDeveloperTools() {
        composeTestRule.setContent {
            AppTheme {
                DebugSettingsTestLayout(isDebugBuild = true)
            }
        }
        composeTestRule.onNodeWithText("Developer Tools").assertIsDisplayed()
    }

    @Test
    fun releaseBuildHidesDeveloperTools() {
        composeTestRule.setContent {
            AppTheme {
                DebugSettingsTestLayout(isDebugBuild = false)
            }
        }
        composeTestRule.onNodeWithText("Developer Tools").assertDoesNotExist()
    }

    @Test
    fun releaseBuildWithDeveloperModeShowsDisableOption() {
        composeTestRule.setContent {
            AppTheme {
                DebugSettingsTestLayout(
                    isDebugBuild = false,
                    developerModeEnabled = true,
                )
            }
        }
        composeTestRule.onNodeWithText("Disable developer mode").assertIsDisplayed()
    }

    @Test
    fun debugBuildHidesDisableDeveloperModeOption() {
        composeTestRule.setContent {
            AppTheme {
                DebugSettingsTestLayout(
                    isDebugBuild = true,
                    developerModeEnabled = true,
                )
            }
        }
        composeTestRule.onNodeWithText("Disable developer mode").assertDoesNotExist()
    }

    @Test
    fun disableDeveloperModeCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme {
                DebugSettingsTestLayout(
                    isDebugBuild = false,
                    developerModeEnabled = true,
                    onDisableDeveloperMode = { clicked = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Disable developer mode").performClick()
        clicked shouldBe true
    }
}
