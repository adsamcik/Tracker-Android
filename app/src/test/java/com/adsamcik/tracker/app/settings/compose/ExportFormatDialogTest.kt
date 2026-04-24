package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.ExportFormat
import com.adsamcik.tracker.app.settings.components.ExportFormatDialog
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportFormatDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysDialogTitle() {
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Choose Export Format").assertIsDisplayed()
    }

    @Test
    fun displaysAllFormatOptions() {
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = {}
                )
            }
        }
        composeTestRule.onNodeWithText("GPX (Recommended)").assertIsDisplayed()
        composeTestRule.onNodeWithText("KML").assertIsDisplayed()
        composeTestRule.onNodeWithText("Database").assertIsDisplayed()
    }

    @Test
    fun displaysFormatDescriptions() {
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = {}
                )
            }
        }
        composeTestRule.onNodeWithText(
            "For GPS devices, fitness apps, and universal compatibility"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "For Google Earth and geographic visualization"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Complete backup including all data and settings"
        ).assertIsDisplayed()
    }

    @Test
    fun displaysCancelButton() {
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun selectingGpx_callsOnFormatSelectedWithGpx() {
        var selectedFormat: ExportFormat? = null
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it }
                )
            }
        }
        composeTestRule.onNodeWithText("GPX (Recommended)").performClick()
        assertEquals(ExportFormat.GPX, selectedFormat)
    }

    @Test
    fun selectingKml_callsOnFormatSelectedWithKml() {
        var selectedFormat: ExportFormat? = null
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it }
                )
            }
        }
        composeTestRule.onNodeWithText("KML").performClick()
        assertEquals(ExportFormat.KML, selectedFormat)
    }

    @Test
    fun selectingDatabase_callsOnFormatSelectedWithDatabase() {
        var selectedFormat: ExportFormat? = null
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it }
                )
            }
        }
        composeTestRule.onNodeWithText("Database").performClick()
        assertEquals(ExportFormat.DATABASE, selectedFormat)
    }

    @Test
    fun selectingFormat_alsoDismissesDialog() {
        var dismissed = false
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = { dismissed = true },
                    onFormatSelected = {}
                )
            }
        }
        composeTestRule.onNodeWithText("GPX (Recommended)").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun cancelButton_callsOnDismiss() {
        var dismissed = false
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = { dismissed = true },
                    onFormatSelected = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }
}
