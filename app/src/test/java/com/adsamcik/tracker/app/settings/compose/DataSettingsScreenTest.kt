package com.adsamcik.tracker.app.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.app.settings.components.SectionHeader
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.app.settings.components.SwitchSettingsItem
import com.adsamcik.tracker.shared.utils.style.compose.AppTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the data settings screen layout.
 * Since DataSettingsScreen uses hiltViewModel(), we test with a reconstructed layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataSettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun DataSettingsTestLayout(
        autoCleanupEnabled: Boolean = false,
        dataRetentionYears: Int = 1,
        incrementalBackupsEnabled: Boolean = true,
        smartGoalNotificationsEnabled: Boolean = true,
        onExportClick: () -> Unit = {},
        onImportClick: () -> Unit = {},
        onAutoCleanupChanged: (Boolean) -> Unit = {},
        onIncrementalBackupsChanged: (Boolean) -> Unit = {},
        onSmartGoalNotificationsChanged: (Boolean) -> Unit = {},
        onDeleteAllClick: () -> Unit = {},
        onResetWatermarksClick: () -> Unit = {},
    ) {
        var showDeleteDialog by remember { mutableStateOf(false) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("dataSettingsList"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            // Export section
            item { SectionHeader("Export") }
            item {
                SettingsItem(
                    title = "Export data",
                    subtitle = "Export tracking data",
                    icon = Icons.Default.FileUpload,
                    onClick = onExportClick,
                )
            }
            item {
                SwitchSettingsItem(
                    title = "Incremental backups",
                    subtitle = "Only export new data since last export",
                    checked = incrementalBackupsEnabled,
                    onCheckedChange = onIncrementalBackupsChanged,
                )
            }
            item {
                SettingsItem(
                    title = "Reset export watermarks",
                    subtitle = "Next export will include all data",
                    icon = Icons.Default.Refresh,
                    onClick = onResetWatermarksClick,
                )
            }

            // Import section
            item {
                SettingsItem(
                    title = "Import",
                    subtitle = "Import from GPX, KML, ZIP",
                    icon = Icons.Default.FileDownload,
                    onClick = onImportClick,
                )
            }

            // Data management section
            item { SectionHeader("Data Management") }
            item {
                val autoCleanupSummary = if (dataRetentionYears == 0) {
                    "Keep data forever; auto-cleanup will not delete old data"
                } else {
                    "Automatically remove data older than $dataRetentionYears " +
                        if (dataRetentionYears == 1) "year" else "years"
                }
                SwitchSettingsItem(
                    title = "Auto cleanup old data",
                    subtitle = autoCleanupSummary,
                    checked = autoCleanupEnabled,
                    onCheckedChange = onAutoCleanupChanged,
                )
            }
            item {
                SwitchSettingsItem(
                    title = "Smart goal notifications",
                    checked = smartGoalNotificationsEnabled,
                    onCheckedChange = onSmartGoalNotificationsChanged,
                )
            }

            // Danger zone
            item { SectionHeader("Danger Zone") }
            item {
                SettingsItem(
                    title = "Delete all collected data",
                    subtitle = "This action cannot be undone",
                    icon = Icons.Default.DeleteForever,
                    onClick = {
                        showDeleteDialog = true
                        onDeleteAllClick()
                    },
                )
            }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete all collected data") },
                text = { Text("Are you sure you want to delete all collected data?") },
                confirmButton = {
                    Button(
                        onClick = { showDeleteDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }

    @Test
    fun displaysExportSection() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Export data").assertIsDisplayed()
    }

    @Test
    fun displaysImportSetting() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Import", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysDataManagementSection() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithText("Data Management").assertIsDisplayed()
        composeTestRule.onNodeWithText("Auto cleanup old data").assertIsDisplayed()
    }

    @Test
    fun autoCleanupSummaryReflectsSelectedYears() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(dataRetentionYears = 3) }
        }
        composeTestRule.onNodeWithText("Automatically remove data older than 3 years").assertIsDisplayed()
    }

    @Test
    fun autoCleanupSummaryReflectsKeepForever() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(dataRetentionYears = 0) }
        }
        composeTestRule.onNodeWithText("Keep data forever", substring = true).assertIsDisplayed()
    }

    @Test
    fun displaysDangerZone() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))
        composeTestRule.onNodeWithText("Delete all collected data").assertIsDisplayed()
    }

    @Test
    fun exportClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(onExportClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Export data").performClick()
        clicked shouldBe true
    }

    @Test
    fun importClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout(onImportClick = { clicked = true }) }
        }
        composeTestRule.onNodeWithText("Import", substring = true).performClick()
        clicked shouldBe true
    }

    @Test
    fun autoCleanupToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onAutoCleanupChanged = { newValue = it })
            }
        }
        composeTestRule.onNodeWithText("Auto cleanup old data").performClick()
        newValue shouldBe true // Was false
    }

    @Test
    fun incrementalBackupsToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onIncrementalBackupsChanged = { newValue = it })
            }
        }
        composeTestRule.onNodeWithText("Incremental backups").performClick()
        newValue shouldBe false // Was true
    }

    @Test
    fun smartGoalNotificationsToggleCallsCallback() {
        var newValue: Boolean? = null
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onSmartGoalNotificationsChanged = { newValue = it })
            }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Smart goal notifications"))
        composeTestRule.onNodeWithText("Smart goal notifications").performClick()
        newValue shouldBe false // Was true
    }

    @Test
    fun deleteAllShowsConfirmationDialog() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))
        composeTestRule.onNodeWithText("Delete all collected data").performClick()
        composeTestRule.onNodeWithText("Are you sure", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun deleteDialogDismissesOnCancel() {
        composeTestRule.setContent {
            AppTheme { DataSettingsTestLayout() }
        }
        composeTestRule.onNodeWithTag("dataSettingsList")
            .performScrollToNode(hasText("Delete all collected data"))
        composeTestRule.onNodeWithText("Delete all collected data").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Are you sure", substring = true).assertDoesNotExist()
    }

    @Test
    fun resetWatermarksClickCallsCallback() {
        var clicked = false
        composeTestRule.setContent {
            AppTheme {
                DataSettingsTestLayout(onResetWatermarksClick = { clicked = true })
            }
        }
        composeTestRule.onNodeWithText("Reset export watermarks").performClick()
        clicked shouldBe true
    }
}
