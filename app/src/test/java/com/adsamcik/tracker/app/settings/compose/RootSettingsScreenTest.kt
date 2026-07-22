package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.adsamcik.tracker.app.settings.SettingsScreen
import com.adsamcik.tracker.app.settings.root.RootSettingsContent
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsState
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.type.SpeedFormat
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultState = TrackerSettingsState(
        autoUnitSwitch = false,
        lengthSystem = LengthSystem.Metric,
        speedFormat = SpeedFormat.Hour,
    )

    private fun setContentWithDefaults(
        showDebug: Boolean = false,
        showTraceboxTrial: Boolean = false,
        developerModeEnabled: Boolean = false,
        onNavigate: (SettingsScreen) -> Unit = {},
        onNavigateToActivities: () -> Unit = {},
        onNavigateToAbout: () -> Unit = {},
        onAutoUnitSwitchChanged: (Boolean) -> Unit = {},
    ) {
        composeTestRule.setContent {
            AppTheme {
                RootSettingsContent(
                    state = defaultState,
                    showDebug = showDebug,
                    showTraceboxTrial = showTraceboxTrial,
                    developerModeEnabled = developerModeEnabled,
                    onNavigate = onNavigate,
                    onNavigateToActivities = onNavigateToActivities,
                    onNavigateToAbout = onNavigateToAbout,
                    onAutoUnitSwitchChanged = onAutoUnitSwitchChanged,
                    onLengthSystemSelected = {},
                    onSpeedFormatSelected = {},
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeTestRule.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun displaysCoreSettingsGroup() {
        setContentWithDefaults()
        composeTestRule.onNodeWithText("Core", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Tracking", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Data", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysModuleSettingsGroup() {
        setContentWithDefaults()
        scrollTo("Map")
        composeTestRule.onNodeWithText("Map", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Game", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysAboutSection() {
        setContentWithDefaults()
        scrollTo("Privacy Policy")
        composeTestRule.onNodeWithText("About", substring = true).assertExists()
        composeTestRule.onNodeWithText("Privacy Policy", substring = true).assertIsDisplayed()
    }

    @Test
    fun debugGroupShownWhenEnabled() {
        setContentWithDefaults(showDebug = true, developerModeEnabled = true)
        scrollTo("Debug")
        composeTestRule.onNodeWithText("Debug", substring = true).assertIsDisplayed()
    }

    @Test
    fun debugGroupHiddenWhenDisabled() {
        setContentWithDefaults(showDebug = false)
        composeTestRule.onNodeWithText("Debug", substring = true).assertDoesNotExist()
    }

    @Test
    fun navigateToTrackingCallsCallback() {
        var navigatedTo: SettingsScreen? = null
        setContentWithDefaults(onNavigate = { navigatedTo = it })
        composeTestRule.onNodeWithText("Tracking", substring = true).performClick()
        navigatedTo shouldBe SettingsScreen.Tracking
    }

    @Test
    fun navigateToDataCallsCallback() {
        var navigatedTo: SettingsScreen? = null
        setContentWithDefaults(onNavigate = { navigatedTo = it })
        composeTestRule.onNodeWithText("Data", substring = true).performClick()
        navigatedTo shouldBe SettingsScreen.Data
    }

    @Test
    fun navigateToTraceboxTrialCallsCallback() {
        var navigatedTo: SettingsScreen? = null
        setContentWithDefaults(
            showTraceboxTrial = true,
            onNavigate = { navigatedTo = it },
        )
        scrollTo("Tracebox alpha trial")

        composeTestRule.onNodeWithText("Tracebox alpha trial").performClick()

        navigatedTo shouldBe SettingsScreen.TraceboxTrial
    }

    @Test
    fun traceboxTrialHiddenWhenUnavailable() {
        setContentWithDefaults(showTraceboxTrial = false)
        scrollTo("Privacy Policy")

        composeTestRule.onNodeWithText("Tracebox alpha trial").assertDoesNotExist()
    }

    @Test
    fun autoUnitSwitchToggleCallsCallback() {
        var newValue: Boolean? = null
        setContentWithDefaults(onAutoUnitSwitchChanged = { newValue = it })
        scrollTo("Automatic unit switching")
        composeTestRule.onNodeWithText("Automatic unit switching", substring = true).performClick()
        newValue shouldBe true
    }

    @Test
    fun displaysVersionInfo() {
        setContentWithDefaults()
        scrollTo("Version information")
        composeTestRule.onNodeWithText("Version information", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysLanguageSetting() {
        setContentWithDefaults()
        scrollTo("Application language")
        composeTestRule.onNodeWithText("Application language", substring = true).assertIsDisplayed()
    }
}
