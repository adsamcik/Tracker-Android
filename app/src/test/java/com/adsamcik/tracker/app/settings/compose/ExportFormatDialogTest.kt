package com.adsamcik.tracker.app.settings.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.adsamcik.tracker.app.settings.components.ExportFormat
import com.adsamcik.tracker.app.settings.components.ExportFormatDialog
import com.adsamcik.tracker.impexp.format.FormatRegistry
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
        composeTestRule.onNodeWithText("JSON").assertIsDisplayed()
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
            "Full-precision route points for GPS devices, fitness apps, and universal compatibility"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Full-precision route points for Google Earth and geographic visualization"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Streaming locations and session summaries; import merges data and regenerates some metadata"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Raw SQLite backup with all data and settings; import merges compatible rows, not a full restore"
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
        composeTestRule.onNodeWithText("Continue export").performClick()
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
        composeTestRule.onNodeWithText("Continue export").performClick()
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
        composeTestRule.onNodeWithText("Continue export").performClick()
        assertEquals(ExportFormat.DATABASE, selectedFormat)
    }

    @Test
    fun selectingJson_callsOnFormatSelectedWithJson() {
        var selectedFormat: ExportFormat? = null
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it }
                )
            }
        }
        composeTestRule.onNodeWithText("JSON").performClick()
        composeTestRule.onNodeWithText("Continue export").performClick()
        assertEquals(ExportFormat.JSON, selectedFormat)
    }

    @Test
    fun supportedExportFormats_matchFormatRegistry() {
        val registryIds = FormatRegistry.allExportFormats().map { it.id }.toSet()
        val dialogIds = ExportFormat.supportedExportFormats().map { it.formatId }.toSet()

        assertEquals(registryIds, dialogIds)
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
        composeTestRule.onNodeWithText("Continue export").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun selectingDatabase_showsSensitivityWarningBeforeCallback() {
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
        composeTestRule.onNodeWithText("Export precise location data?").assertIsDisplayed()
        composeTestRule.onNodeWithText("precise locations, Wi-Fi/cell radio data", substring = true)
            .assertIsDisplayed()
        assertEquals(null, selectedFormat)
    }

    @Test
    fun selectingJson_showsSensitivityWarningBeforeCallback() {
        var selectedFormat: ExportFormat? = null
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it }
                )
            }
        }
        composeTestRule.onNodeWithText("JSON").performClick()
        composeTestRule.onNodeWithText("Export precise location data?").assertIsDisplayed()
        composeTestRule.onNodeWithText("full-precision locations", substring = true)
            .assertIsDisplayed()
        assertEquals(null, selectedFormat)
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
