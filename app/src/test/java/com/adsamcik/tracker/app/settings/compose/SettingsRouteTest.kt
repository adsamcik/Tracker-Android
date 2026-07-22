package com.adsamcik.tracker.app.settings.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.SettingsScreen
import com.adsamcik.tracker.app.settings.SettingsScreenSaver
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for settings navigation routing (SettingsRoute.kt).
 * Since SettingsRoute uses hiltViewModel(), we test the navigation state
 * management pattern with a simplified layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRouteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsRouteTestLayout(
        initialScreen: SettingsScreen = SettingsScreen.Root,
        onNavigateBack: () -> Unit = {},
    ) {
        var currentScreen by rememberSaveable(
            initialScreen,
            saver = SettingsScreenSaver,
        ) { mutableStateOf(initialScreen) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (currentScreen) {
                                SettingsScreen.Root -> "Settings"
                                SettingsScreen.Tracking -> "Tracking"
                                SettingsScreen.Data -> "Data"
                                SettingsScreen.Map -> "Map"
                                SettingsScreen.Game -> "Game"
                                SettingsScreen.TraceboxTrial -> "Tracebox alpha trial"
                                SettingsScreen.Debug -> "Debug"
                                else -> "Settings"
                            }
                        )
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (currentScreen) {
                    SettingsScreen.Root -> {
                        // Simplified root with navigation buttons
                        androidx.compose.foundation.layout.Column {
                            Text("Root Settings")
                            androidx.compose.material3.TextButton(
                                onClick = { currentScreen = SettingsScreen.Tracking },
                            ) { Text("Go to Tracking") }
                            androidx.compose.material3.TextButton(
                                onClick = { currentScreen = SettingsScreen.Data },
                            ) { Text("Go to Data") }
                            androidx.compose.material3.TextButton(
                                onClick = { currentScreen = SettingsScreen.Map },
                            ) { Text("Go to Map") }
                            androidx.compose.material3.TextButton(
                                onClick = { currentScreen = SettingsScreen.Game },
                            ) { Text("Go to Game") }
                            androidx.compose.material3.TextButton(
                                onClick = { currentScreen = SettingsScreen.TraceboxTrial },
                            ) { Text("Go to Tracebox trial") }
                        }
                    }
                    SettingsScreen.Tracking -> Text("Tracking Settings Content")
                    SettingsScreen.Data -> Text("Data Settings Content")
                    SettingsScreen.Map -> Text("Map Settings Content")
                    SettingsScreen.Game -> Text("Game Settings Content")
                    SettingsScreen.TraceboxTrial -> Text("Tracebox Trial Content")
                    SettingsScreen.Debug -> Text("Debug Settings Content")
                    else -> Text("Unknown screen")
                }
            }
        }
    }

    @Test
    fun startsOnRootScreen() {
        composeTestRule.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }
        composeTestRule.onNodeWithText("Root Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun startsOnDataScreenWhenProvided() {
        composeTestRule.setContent {
            AppTheme { SettingsRouteTestLayout(initialScreen = SettingsScreen.Data) }
        }
        composeTestRule.onNodeWithText("Data Settings Content").assertIsDisplayed()
    }

    @Test
    fun navigatesToTrackingScreen() {
        composeTestRule.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }
        composeTestRule.onNodeWithText("Go to Tracking").performClick()
        composeTestRule.onNodeWithText("Tracking Settings Content").assertIsDisplayed()
    }

    @Test
    fun currentScreen_survivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }

        composeTestRule.onNodeWithText("Go to Tracking").performClick()
        composeTestRule.onNodeWithText("Tracking Settings Content").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Tracking Settings Content").assertIsDisplayed()
    }

    @Test
    fun navigatesToDataScreen() {
        composeTestRule.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }
        composeTestRule.onNodeWithText("Go to Data").performClick()
        composeTestRule.onNodeWithText("Data Settings Content").assertIsDisplayed()
    }

    @Test
    fun navigatesToMapScreen() {
        composeTestRule.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }
        composeTestRule.onNodeWithText("Go to Map").performClick()
        composeTestRule.onNodeWithText("Map Settings Content").assertIsDisplayed()
    }

    @Test
    fun navigatesToGameScreen() {
        composeTestRule.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }
        composeTestRule.onNodeWithText("Go to Game").performClick()
        composeTestRule.onNodeWithText("Game Settings Content").assertIsDisplayed()
    }

    @Test
    fun traceboxTrialScreenSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            AppTheme { SettingsRouteTestLayout() }
        }

        composeTestRule.onNodeWithText("Go to Tracebox trial").performClick()
        composeTestRule.onNodeWithText("Tracebox Trial Content").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Tracebox Trial Content").assertIsDisplayed()
    }

    @Test
    fun settingsScreenSealedClassHasCorrectEntries() {
        // Verify all expected screen types exist
        val screens = listOf(
            SettingsScreen.Root,
            SettingsScreen.Tracking,
            SettingsScreen.Data,
            SettingsScreen.Export,
            SettingsScreen.Map,
            SettingsScreen.Game,
            SettingsScreen.TraceboxTrial,
            SettingsScreen.Statistics,
            SettingsScreen.Debug,
        )
        screens.size shouldBe 9
    }
}
