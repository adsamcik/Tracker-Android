package com.adsamcik.tracker.app.settings.compose

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
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
        composeTestRule.onNodeWithText("GPX").assertIsDisplayed()
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
            "Location points for GPS apps. Other tracking sources and coverage details are not included."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Timed location points for maps. Other tracking sources and coverage details are not included."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Locations, legacy Wi-Fi and Cell readings, and session summaries. Includes whole sessions that overlap chosen dates; other sources are omitted."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "All saved data. Settings are excluded."
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
        composeTestRule.onNodeWithText("GPX").performClick()
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
        assertEquals(ExportFormat.JSON, selectedFormat)
    }

    @Test
    fun supportedExportFormats_matchFormatRegistry() {
        val registryIds = FormatRegistry.allExportFormats().map { it.id }.toSet()
        val dialogIds = ExportFormat.supportedExportFormats().map { it.formatId }.toSet()

        assertEquals(registryIds, dialogIds)
    }

    @Test
    fun wifiSelectsOnlyTheRegisteredWifiExport() {
        selectRadioFormat(ExportFormat.WIFI, "Wi-Fi", "saved Wi-Fi names and access-point IDs")
    }

    @Test
    fun cellSelectsOnlyTheRegisteredCellExport() {
        selectRadioFormat(ExportFormat.CELL, "Cell", "saved tower IDs and signal measurements")
    }

    private fun selectRadioFormat(format: ExportFormat, label: String, contents: String) {
        var selectedFormat: ExportFormat? = null
        var dismissed = false
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = { dismissed = true },
                    onFormatSelected = { selectedFormat = it },
                )
            }
        }
        composeTestRule.onNodeWithText(contents, substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(format, selectedFormat)
        assertTrue(dismissed)
        composeTestRule.onNodeWithText("Export network details?").assertDoesNotExist()
    }

    @Test
    fun portableStepsCanBeSelectedWithoutClaimingRouteCoordinates() {
        selectPortableFormat(ExportFormat.PORTABLE_STEPS, "Portable Steps", "Steps history")
    }

    @Test
    fun portableAmbientStepsUsesNativePickerRouteWithFactualPrivacyCopy() {
        selectPortableFormat(
            ExportFormat.PORTABLE_AMBIENT_STEPS,
            "Portable Ambient Steps",
            "Ambient step counts",
            "activity time",
            "dates",
            "stored time-zone context",
        )
    }

    @Test
    fun portableActivityCanBeSelectedWithoutClaimingRouteCoordinates() {
        selectPortableFormat(ExportFormat.PORTABLE_ACTIVITY, "Portable Activity", "Captured movement bands")
    }

    @Test
    fun portablePressureCanBeSelectedFromScrollableFormatList() {
        selectPortableFormat(ExportFormat.PORTABLE_PRESSURE, "Portable Pressure", "pressure measurements")
    }

    private fun selectPortableFormat(
        format: ExportFormat,
        label: String,
        vararg content: String,
    ) {
        var selectedFormat: ExportFormat? = null
        val context = ApplicationProvider.getApplicationContext<Context>()
        val description = context.getString(format.descriptionRes)
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(onDismiss = {}, onFormatSelected = { selectedFormat = it })
            }
        }

        content.forEach { expected ->
            assertTrue(description.contains(expected))
        }
        assertTrue(description.contains("without route coordinates"))
        composeTestRule.onNodeWithText(description).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(format, selectedFormat)
        composeTestRule.onNodeWithText("Export tracked history?").assertDoesNotExist()
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
        composeTestRule.onNodeWithText("GPX").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun selectingDatabase_forwardsOnceWithoutEarlyConfirmation() {
        var selectedFormat: ExportFormat? = null
        var selections = 0
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it; selections++ }
                )
            }
        }
        composeTestRule.onNodeWithText("Database").performClick()
        assertEquals(ExportFormat.DATABASE, selectedFormat)
        assertEquals(1, selections)
        composeTestRule.onNodeWithText("Export precise location data?").assertDoesNotExist()
    }

    @Test
    fun selectingJson_forwardsOnceWithoutEarlyConfirmation() {
        var selectedFormat: ExportFormat? = null
        var selections = 0
        composeTestRule.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(
                    onDismiss = {},
                    onFormatSelected = { selectedFormat = it; selections++ }
                )
            }
        }
        composeTestRule.onNodeWithText("JSON").performClick()
        assertEquals(ExportFormat.JSON, selectedFormat)
        assertEquals(1, selections)
        composeTestRule.onNodeWithText("Export precise location data?").assertDoesNotExist()
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
