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
import com.adsamcik.tracker.app.settings.components.ExpandableSection
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SliderSettingsItemWithHelp
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the map settings screen layout.
 * Since MapSettingsScreen uses hiltViewModel(), we test with a reconstructed layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun MapSettingsTestLayout(
        basemapPath: String? = null,
        skiInfraLoaded: Boolean = false,
        onBasemapClick: () -> Unit = {},
        onSkiInfraClick: () -> Unit = {},
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            // Basemap setting
            item {
                SettingsItem(
                    title = "Basemap",
                    subtitle = if (basemapPath != null) "Custom" else "Default",
                    onClick = onBasemapClick,
                )
            }

            // Ski infrastructure
            item {
                SettingsItem(
                    title = "Ski Infrastructure",
                    subtitle = if (skiInfraLoaded) "Loaded" else "Not loaded",
                    onClick = onSkiInfraClick,
                )
            }

            // Advanced section
            item {
                ExpandableSection(
                    title = "Advanced",
                    initiallyExpanded = false,
                ) {
                    SliderSettingsItemWithHelp(
                        title = "Quality",
                        value = 1f,
                        valueRange = 0f..4f,
                        steps = 3,
                        valueLabel = { "%.1fx".format(it) },
                        onValueChange = {},
                        helpTextRes = com.adsamcik.tracker.map.R.string.help_map_quality,
                    )
                    SliderSettingsItemWithHelp(
                        title = "Max Heat",
                        value = 2f,
                        valueRange = 0f..4f,
                        steps = 3,
                        valueLabel = { "${it.toInt()}" },
                        onValueChange = {},
                        helpTextRes = com.adsamcik.tracker.map.R.string.help_max_heat_points,
                    )
                }
            }
        }
    }

    @Test
    fun displaysBasemapSetting() {
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Basemap").assertIsDisplayed()
        composeTestRule.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun basemapShowsCustomWhenPathSet() {
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout(basemapPath = "/path/to/map") }
        }
        composeTestRule.onNodeWithText("Custom").assertIsDisplayed()
    }

    @Test
    fun displaysSkiInfrastructureSetting() {
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Ski Infrastructure").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not loaded").assertIsDisplayed()
    }

    @Test
    fun skiInfraShowsLoadedState() {
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout(skiInfraLoaded = true) }
        }
        composeTestRule.onNodeWithText("Loaded").assertIsDisplayed()
    }

    @Test
    fun basemapClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout(onBasemapClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Basemap").performClick()
        clicked shouldBe true
    }

    @Test
    fun skiInfraClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout(onSkiInfraClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Ski Infrastructure").performClick()
        clicked shouldBe true
    }

    @Test
    fun advancedSectionCollapsedByDefault() {
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Advanced").assertIsDisplayed()
        composeTestRule.onNodeWithText("Quality").assertDoesNotExist()
    }

    @Test
    fun advancedSectionExpandsOnClick() {
        composeTestRule.setContent {
            AppTheme { MapSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Advanced").performClick()
        composeTestRule.onNodeWithText("Quality").assertIsDisplayed()
        composeTestRule.onNodeWithText("Max Heat").assertIsDisplayed()
    }
}
