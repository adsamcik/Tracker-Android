package com.adsamcik.tracker.app.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the configuration-change resilience the detail screens rely on after
 * their portrait locks were removed for API 37. A selection persisted by a
 * stable key via [rememberSaveable] must survive activity recreation
 * (rotation / resize / multi-window) — mirroring how ThirdPartyLicensesActivity
 * keeps its open license dialog by remembering the selected license name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetailScreenStateRestorationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val items = listOf("Alpha", "Bravo", "Charlie")

    @Composable
    private fun SelectionScreen() {
        var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
        Column {
            items.forEach { name ->
                Text(
                    text = name,
                    modifier = Modifier
                        .testTag("item_$name")
                        .clickable { selectedName = name },
                )
            }
            selectedName?.let {
                Text(text = "Selected: $it", modifier = Modifier.testTag("selection"))
            }
        }
    }

    @Test
    fun selection_survivesRecreation() {
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent { SelectionScreen() }

        composeTestRule.onNodeWithTag("item_Bravo").performClick()
        composeTestRule.onNodeWithText("Selected: Bravo").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        // Without rememberSaveable the selection would be lost here.
        composeTestRule.onNodeWithText("Selected: Bravo").assertIsDisplayed()
    }

    @Test
    fun selection_isEmptyByDefault() {
        composeTestRule.setContent { SelectionScreen() }
        composeTestRule.onNodeWithTag("selection").assertDoesNotExist()
    }
}
