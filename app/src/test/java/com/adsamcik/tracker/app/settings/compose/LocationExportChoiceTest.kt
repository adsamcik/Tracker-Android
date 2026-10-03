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
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationExportChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Location choice selects only its exact saved-data file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var selected: ExportFormat? = null
        compose.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(onDismiss = {}, onFormatSelected = { selected = it })
            }
        }
        compose.onNodeWithText(context.getString(ExportFormat.LOCATION.displayNameRes))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(ExportFormat.LOCATION.sensitivityMessageRes))
            .assertIsDisplayed()
        assertEquals(null, selected)
        compose.onNodeWithText(context.getString(com.adsamcik.tracker.R.string.export_sensitivity_confirm))
            .performClick()
        assertEquals(ExportFormat.LOCATION, selected)

        val exporter = assertNotNull(FormatRegistry.exporterFor(ExportFormat.LOCATION.formatId))
        assertEquals("trackerlocation", exporter.extension)
        assertEquals(false, exporter.requiresLocationData)
        assertEquals(true, exporter.selectsCalendarDates)
        assertEquals(
            context.getString(ExportFormat.LOCATION.sensitivityMessageRes),
            context.getString(exporter.sensitivityMessageRes),
        )
        // Exercise the export activity's actual reflective no-argument contract.
        val reconstructed = exporter.javaClass.getConstructor().newInstance()
        assertEquals(exporter.extension, reconstructed.extension)
        assertEquals(exporter.mimeType, reconstructed.mimeType)
    }

    @Test fun `dismissing Location confirmation does not choose another format`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var selected: ExportFormat? = null
        compose.setContent {
            AppTheme(useDynamicColor = false) {
                ExportFormatDialog(onDismiss = {}, onFormatSelected = { selected = it })
            }
        }
        compose.onNodeWithText(context.getString(ExportFormat.LOCATION.displayNameRes))
            .performScrollTo().performClick()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        assertEquals(null, selected)
        compose.onNodeWithText(context.getString(ExportFormat.LOCATION.displayNameRes))
            .performScrollTo().assertIsDisplayed()
    }
}
