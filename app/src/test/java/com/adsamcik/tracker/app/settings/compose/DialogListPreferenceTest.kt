package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.DialogListPreference
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogListPreferenceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val entries = listOf("Metric", "Imperial", "Nautical")
    private val entryValues = listOf("metric", "imperial", "nautical")

    @Test
    fun displaysCurrentValue() {
        composeTestRule.setContent {
            AppTheme {
                DialogListPreference(
                    title = "Unit System",
                    currentValue = "metric",
                    entries = entries,
                    entryValues = entryValues,
                    onValueChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Metric").assertIsDisplayed()
    }

    @Test
    fun clickOpensDialog() {
        composeTestRule.setContent {
            AppTheme {
                DialogListPreference(
                    title = "Unit System",
                    currentValue = "metric",
                    entries = entries,
                    entryValues = entryValues,
                    onValueChange = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Unit System").performClick()
        // Dialog shows all options
        composeTestRule.onNodeWithText("Imperial").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nautical").assertIsDisplayed()
    }

    @Test
    fun selectAndConfirm_callsCallback() {
        var selectedIndex: Int? = null
        composeTestRule.setContent {
            AppTheme {
                DialogListPreference(
                    title = "Unit System",
                    currentValue = "metric",
                    entries = entries,
                    entryValues = entryValues,
                    onValueChange = { selectedIndex = it },
                )
            }
        }
        // Open dialog
        composeTestRule.onNodeWithText("Unit System").performClick()
        // Select Imperial
        composeTestRule.onNodeWithText("Imperial").performClick()
        // Confirm
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.waitForIdle()
        selectedIndex shouldBe 1
    }
}
